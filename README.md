# cicd-demo-ig5

A CI/CD demonstration project for a Spring Boot application, automated end-to-end through Jenkins running on a local Kubernetes cluster (Kind). The pipeline covers compilation, unit testing, code coverage, static analysis, container security scanning, image publishing, and deployment to Kubernetes.

---

## Table of Contents

1. [Architecture](#architecture)
2. [Prerequisites](#prerequisites)
3. [Initial Cluster Setup](#initial-cluster-setup)
4. [Jenkins Credentials](#jenkins-credentials)
5. [Pipeline Stages](#pipeline-stages)
6. [Security Scanning — .trivyignore](#security-scanning--trivyignore)
7. [Kubernetes Manifest — deployment.tmpl.yml](#kubernetes-manifest--deploymenttmpyml)
8. [Accessing the Application](#accessing-the-application)
9. [Troubleshooting](#troubleshooting)

---

## Architecture

The pipeline runs inside a Jenkins Kubernetes agent pod. Each pod is provisioned on demand and contains five containers that share a common network namespace:

```
Jenkins Agent Pod
├── maven      — compiles the project and runs tests / SonarQube analysis
├── dind       — Docker-in-Docker daemon (privileged); owns the Docker socket
├── docker     — Docker CLI client; connects to dind via tcp://localhost:2375
├── trivy      — vulnerability scanner; also connects to dind to inspect images
└── kubectl    — deploys the built image to the Kubernetes cluster via in-cluster auth
```

The `dind` container mounts an `emptyDir` volume at `/var/lib/docker` so image layers persist for the duration of the build but are discarded when the pod terminates.

The Kubernetes cluster topology after a successful pipeline run:

```
External traffic
      |
 NodePort :30080
      |
  mi-app-service  (ClusterIP port 80 -> Pod port 8080)
      |
  mi-app-deployment
      |
  Pod (eclipse-temurin:17-jre-alpine + app.jar)
```

---

## Prerequisites

- [Kind](https://kind.sigs.k8s.io/) cluster running locally
- Jenkins deployed inside the cluster (Helm chart or manual)
- SonarQube deployed inside the cluster, accessible at `http://sonarqube-sonarqube:9000`
- `kubectl` configured on the host machine pointing to the Kind cluster
- A Docker Hub account for image publishing

---

## Initial Cluster Setup

The `default` service account in the `default` namespace must have cluster-admin rights so that the `kubectl` container inside the Jenkins agent pod can create and update Deployments and Services. Apply the ClusterRoleBinding once from your host machine before running the pipeline:

```bash
kubectl apply -f jenkins/defaultRB.yml
```

Verify the binding is active:

```bash
kubectl get clusterrolebinding default-rbac
```

This binding grants the `system:serviceaccount:default:default` principal the `cluster-admin` ClusterRole. Without it, every `kubectl apply` inside the pipeline will be rejected with a Forbidden error.

---

## Jenkins Credentials

Create the following credentials in Jenkins (Manage Jenkins > Credentials) before running the pipeline:

| ID | Type | Purpose |
|----|------|---------|
| `sonarqube-token` | Secret text | SonarQube analysis token (Execute Analysis permission) |
| `sonarqube-user-token` | Secret text | SonarQube API token for Quality Gate and Hotspot queries |
| `docker-registry` | Username / Password | Docker Hub username and password or access token |

---

## Pipeline Stages

The pipeline is defined in `Jenkinsfile`. Every build runs inside a freshly provisioned Kubernetes pod; the pod is destroyed when the pipeline finishes.

### 1. Checkout

```groovy
checkout scm
```

Clones the repository into the agent workspace. This is the source for all subsequent stages.

---

### 2. Build

```bash
mvn clean package -DskipTests
```

Runs inside the `maven` container. Compiles the source code and packages it into a JAR file at `target/cicd-demo-*.jar`. Tests are intentionally skipped here to keep this stage fast; testing is a separate, dedicated stage.

---

### 3. Test

```bash
mvn test
```

Executes the JUnit test suite. The pipeline uses the `junit` post-step to publish the Surefire XML reports regardless of whether the tests pass or fail, so results are always visible in the Jenkins UI. The `allowEmptyResults: true` flag prevents the pipeline from failing when no tests are found.

---

### 4. Jacoco Report

```bash
mvn jacoco:report
```

Generates the code coverage report at `target/site/jacoco/jacoco.xml`. This file is consumed in the next stage by SonarQube to include coverage metrics in the analysis.

---

### 5. SonarQube Analysis

```bash
mvn sonar:sonar \
    -Dsonar.host.url=$SONAR_HOST_URL \
    -Dsonar.projectKey=my-app \
    -Dsonar.token=$SONAR_TOKEN \
    -Dsonar.qualitygate.wait=true \
    -Dsonar.qualitygate.timeout=120 \
    -Dsonar.coverage.jacoco.xmlReportPaths=target/site/jacoco/jacoco.xml
```

Submits the source code, bytecode, and coverage data to SonarQube. The `sonar.qualitygate.wait=true` parameter blocks the Maven process until SonarQube has finished processing the analysis and has returned a Quality Gate status. If the gate fails, Maven exits with a non-zero code and the pipeline stops here.

---

### 6. Quality Gate & Hotspots

Runs inside the `docker` container (which has shell access and can install tools via `apk`). This stage does two independent checks against the SonarQube API:

**Quality Gate** — polls `GET /api/qualitygates/project_status` up to 24 times with 5-second intervals. If the status is anything other than `OK`, the pipeline fails.

**Security Hotspots** — queries `GET /api/hotspots/search` and counts hotspots whose status is not `REVIEWED`. If any unreviewed hotspots exist, the pipeline fails.

This stage acts as an additional gate beyond what the Maven plugin checks, and it uses a separate credential (`sonarqube-user-token`) that is scoped to read-only API access.

---

### 7. Docker Build

```bash
docker build -t mi-app:latest .
```

Runs inside the `docker` container, which communicates with the `dind` daemon via `DOCKER_HOST=tcp://localhost:2375`. The stage waits for the daemon to be ready before issuing the build command.

The `Dockerfile` uses a minimal base image:

```dockerfile
FROM eclipse-temurin:17-jre-alpine
VOLUME /tmp
COPY target/cicd-demo-*.jar app.jar
ENTRYPOINT ["java", "-Djava.security.egd=file:/dev/./unrandom", "-jar", "/app.jar"]
```

`eclipse-temurin:17-jre-alpine` is chosen over a JDK image to minimize the attack surface and image size. The `-Djava.security.egd` flag avoids startup delays caused by blocking entropy reads inside containers.

---

### 8. Trivy Scan

```bash
trivy image --exit-code 1 --severity CRITICAL --ignorefile .trivyignore --timeout 15m mi-app:latest
```

Runs inside the dedicated `trivy` container using `DOCKER_HOST=tcp://localhost:2375` to access the image built in the previous stage. The scan behaviour is:

- `--exit-code 1` — the pipeline fails if any unfixed CRITICAL vulnerability is found that is not suppressed by `.trivyignore`.
- `--severity CRITICAL` — only CRITICAL findings block the pipeline; HIGH findings are reported but not blocking.
- `--timeout 15m` — the Java database download and layer analysis can be slow on first run; 15 minutes avoids a false timeout failure.

A second scan runs unconditionally in the `post { always }` block regardless of the first scan's result:

```bash
trivy image --severity CRITICAL,HIGH --ignore-unfixed --format table --output trivy-report.txt --timeout 15m mi-app:latest || true
```

This generates a human-readable table report that is archived as a build artifact (`trivy-report.txt`). The `--ignore-unfixed` flag excludes vulnerabilities for which no patch is yet available, keeping the report focused on actionable findings.

See [Security Scanning — .trivyignore](#security-scanning--trivyignore) for details on suppressed CVEs.

---

### 9. Push Image

```bash
docker login -u $REGISTRY_USERNAME -p $REGISTRY_PASSWORD
docker tag mi-app:latest $REGISTRY_USERNAME/mi-app:${BUILD_NUMBER}
docker tag mi-app:latest $REGISTRY_USERNAME/mi-app:latest
docker push $REGISTRY_USERNAME/mi-app:${BUILD_NUMBER}
docker push $REGISTRY_USERNAME/mi-app:latest
```

Publishes the verified image to Docker Hub under two tags:

- `$REGISTRY_USERNAME/mi-app:latest` — always points to the most recent build.
- `$REGISTRY_USERNAME/mi-app:<BUILD_NUMBER>` — immutable tag per build, used by the Kubernetes manifest to pull the exact version being deployed.

The image must be pushed to a registry that the Kind cluster nodes can pull from. The nodes cannot access the `dind` daemon directly, so in-cluster image builds are not sufficient on their own.

---

### 10. Deploy

Runs inside the `kubectl` container, which uses in-cluster authentication (the service account token mounted automatically at `/var/run/secrets/kubernetes.io/serviceaccount/`) to communicate with the Kubernetes API server.

The deployment manifest template at `k8s-config/deployment.tmpl.yml` contains placeholder variables (`$APP_NAME`, `$ENV`, `$BUILD_VERSION`, `$REGISTRY_USERNAME`). These are substituted using `sed` before being applied:

```bash
sed \
    -e 's|\$APP_NAME|mi-app|g' \
    -e 's|\$ENV|production|g' \
    -e 's|\$BUILD_VERSION|${BUILD_NUMBER}|g' \
    -e 's|\$REGISTRY_USERNAME|${REGISTRY_USERNAME}|g' \
    k8s-config/deployment.tmpl.yml | kubectl apply -f -
```

After applying, the stage waits for the rollout to complete:

```bash
kubectl rollout status deployment/mi-app-deployment --timeout=5m
```

If the rollout does not reach the ready state within 5 minutes, `kubectl` exits with an error and the pipeline fails. The `post { failure }` block then prints the Deployment status, Pod status, and Pod logs for diagnosis.

---

### Post Actions

| Condition | Action |
|-----------|--------|
| `always` | `cleanWs()` — deletes the workspace directory from the Jenkins agent to prevent data leakage between builds |
| `success` | Prints the `kubectl port-forward` command to access the application |
| `failure` | Prints a reminder to review the logs above |

---

## Security Scanning — .trivyignore

The `.trivyignore` file suppresses specific CVEs from failing the pipeline. It does not hide them from the report; they are still visible in `trivy-report.txt`.

**Current suppressed CVE:**

```
CVE-2016-1000027
```

This CVE is assigned to the Spring Framework's `HttpInvokerServiceExporter`, a legacy remoting component that deserializes Java objects from HTTP requests without type restrictions. The vulnerability is real, but it applies only when that specific component is in use. This application does not use `HttpInvokerServiceExporter`, and no fix is available (the component was deprecated and removed in Spring 6, but the CVE scanner flags it on Spring 5.x JARs). Suppressing it here is a deliberate, documented decision rather than an oversight.

To add additional suppressions, append CVE identifiers one per line to `.trivyignore`.

---

## Kubernetes Manifest — deployment.tmpl.yml

Located at `k8s-config/deployment.tmpl.yml`, this file is a shell-variable template rendered at deploy time by the pipeline. It defines two Kubernetes resources:

### Deployment

```yaml
apiVersion: apps/v1
kind: Deployment
metadata:
  name: mi-app-deployment
spec:
  replicas: 1
  selector:
    matchLabels:
      name: mi-app-app
  template:
    spec:
      containers:
        - name: mi-app-app
          image: <REGISTRY_USERNAME>/mi-app:<BUILD_NUMBER>
          ports:
            - containerPort: 8080
```

The Spring Boot application listens on port 8080 inside the container. Liveness and readiness probes both check `GET /actuator/health` on port 8080. The readiness probe has an initial delay of 30 seconds to allow the JVM and application context to initialize before traffic is routed to the pod.

### Service

```yaml
apiVersion: v1
kind: Service
metadata:
  name: mi-app-service
spec:
  type: NodePort
  selector:
    name: mi-app-app
  ports:
    - port: 80
      targetPort: 8080
      nodePort: 30080
```

Three ports are involved:

| Field | Value | Meaning |
|-------|-------|---------|
| `targetPort` | 8080 | The port the JVM listens on inside the container |
| `port` | 80 | The cluster-internal port; used by other pods with `mi-app-service:80` |
| `nodePort` | 30080 | The port exposed on each node's IP address for external access |

`NodePort` values must be in the range 30000–32767 by default Kubernetes policy. Port 80 cannot be used as a NodePort. The cluster-internal `port: 80` only applies inside the cluster network.

---

## Accessing the Application

After a successful pipeline run, the application is running as a Kubernetes Deployment. The Kind node IP printed in the pipeline logs (`172.x.x.x`) is internal to the Kind Docker network and is not reachable directly from Windows.

The recommended method is `kubectl port-forward`, which tunnels through the Kubernetes API server and works regardless of network topology:

```bash
kubectl port-forward service/mi-app-service 8080:80
```

Then open `http://localhost:8080` in a browser or with curl.

Keep the terminal open for as long as you need access. To run in the background:

```bash
kubectl port-forward service/mi-app-service 8080:80 &
```

To access on port 80 directly (requires elevated privileges on Linux):

```bash
sudo kubectl port-forward service/mi-app-service 80:80
```

---

## Troubleshooting

### Pipeline fails at Deploy with "Forbidden"

The `default` service account does not have the required RBAC permissions. Apply the ClusterRoleBinding from the host machine:

```bash
kubectl delete clusterrolebinding default-rbac 2>/dev/null || true
kubectl apply -f jenkins/defaultRB.yml
kubectl get clusterrolebinding default-rbac
```

### Trivy scan fails with "context deadline exceeded"

The Java vulnerability database download or layer analysis timed out. This typically happens on the first run when the database is not yet cached. The `--timeout 15m` flag should prevent this. If it still occurs, verify that the `trivy` container has sufficient memory (at least 1Gi) and that the cluster node is not under heavy memory pressure.

### SonarQube Quality Gate always returns ERROR

The analysis may not have finished processing by the time the polling loop starts. The loop retries for up to 2 minutes (24 attempts × 5 seconds). If the SonarQube instance is slow or under load, increase the retry count in the `Quality Gate & Hotspots` stage. Also confirm that the `sonarqube-user-token` credential has Browse permission on the project.

### Image pull fails in the Deployment pod

The Deployment pod pulls the image from Docker Hub. Confirm that:
1. The `Push Image` stage completed without errors.
2. The Docker Hub repository is public, or an `imagePullSecret` is configured on the Deployment.
3. The tag referenced in the manifest matches what was pushed (`$REGISTRY_USERNAME/mi-app:<BUILD_NUMBER>`).

### Application is deployed but /actuator/health returns 503

The readiness probe has a 30-second initial delay. Wait at least 30 seconds after the rollout completes before testing. If the probe continues to fail:

```bash
kubectl logs -l application=mi-app --tail=100
kubectl describe pod -l application=mi-app
```
