pipeline {
    agent {
        kubernetes {
            yaml '''
                apiVersion: v1
                kind: Pod
                spec:
                  containers:
                  - name: maven
                    image: maven:3.9-eclipse-temurin-17
                    command:
                    - sleep
                    args:
                    - infinity
                    resources:
                      limits:
                        memory: "2Gi"
                        cpu: "1"
                  - name: dind
                    image: docker:dind
                    securityContext:
                      privileged: true
                    env:
                    - name: DOCKER_TLS_CERTDIR
                      value: ""
                    volumeMounts:
                    - name: docker-storage
                      mountPath: /var/lib/docker
                  - name: docker
                    image: docker:latest
                    command:
                    - sleep
                    args:
                    - infinity
                    env:
                    - name: DOCKER_HOST
                      value: tcp://localhost:2375
                   - name: trivy
                     image: aquasec/trivy:latest
                     command:
                     - sleep
                     args:
                     - infinity
                     env:
                     - name: DOCKER_HOST
                       value: tcp://localhost:2375
                     resources:
                       requests:
                         memory: "1Gi"
                         cpu: "500m"
                       limits:
                         memory: "2Gi"
                         cpu: "1"
                  - name: kubectl
                    image: bitnami/kubectl:latest
                    command:
                    - sleep
                    args:
                    - infinity
                    securityContext:
                      runAsUser: 0
                  volumes:
                  - name: docker-storage
                    emptyDir: {}
            '''
        }
    }

    environment { SONAR_HOST_URL = 'http://sonarqube-sonarqube:9000' }

    stages {
        stage('Checkout') { steps { checkout scm } }

        stage('Build') {
            steps { container('maven') { sh 'mvn clean package -DskipTests' } }
        }

        stage('Test') {
            steps { container('maven') { sh 'mvn test' } }
            post { always { junit allowEmptyResults: true, testResults: 'target/surefire-reports/*.xml' } }
        }

        stage('Jacoco Report') {
            steps { container('maven') { sh 'mvn jacoco:report' } }
        }

        stage('SonarQube Analysis') {
            steps {
                container('maven') {
                    withCredentials([string(credentialsId: 'sonarqube-token', variable: 'SONAR_TOKEN')]) {
                        sh '''
                            mvn sonar:sonar \
                                -Dsonar.host.url=$SONAR_HOST_URL \
                                -Dsonar.projectKey=my-app \
                                -Dsonar.token=$SONAR_TOKEN \
                                -Dsonar.projectName=my-app \
                                -Dsonar.qualitygate.wait=true \
                                -Dsonar.qualitygate.timeout=120 \
                                -Dsonar.coverage.jacoco.xmlReportPaths=target/site/jacoco/jacoco.xml
                        '''
                    }
                }
            }
        }

        stage('Quality Gate & Hotspots') {
            steps {
                container('docker') {
                    withCredentials([
                        string(credentialsId: 'sonarqube-user-token', variable: 'USER_TOKEN')
                    ]) {
                        sh '''
                            apk add --no-cache curl jq > /dev/null 2>&1

                            echo "Verificando Quality Gate..."
                            for i in $(seq 1 24); do
                                QG=$(curl -s -H "Authorization: Bearer $USER_TOKEN" "$SONAR_HOST_URL/api/qualitygates/project_status?projectKey=my-app" 2>/dev/null | jq -r '.projectStatus.status // "ERROR"')
                                if [ "$QG" != "ERROR" ]; then
                                    echo "Quality Gate: $QG"
                                    [ "$QG" != "OK" ] && echo "Quality Gate FAILED!" && exit 1
                                    break
                                fi
                                echo "Esperando Quality Gate... ($i)"
                                sleep 5
                            done

                            echo "Verificando Security Hotspots..."
                            HOTSPOTS=$(curl -s -H "Authorization: Bearer $USER_TOKEN" "$SONAR_HOST_URL/api/hotspots/search?projectKey=my-app" 2>/dev/null | jq '[.hotspots[]? | select(.status != "REVIEWED")] | length' 2>/dev/null || echo 0)
                            echo "Security Hotspots sin revisar: $HOTSPOTS"
                            [ "$HOTSPOTS" -gt 0 ] && echo "Security Hotspots detectados!" && exit 1
                            echo "Sin Security Hotspots pendientes"
                        '''
                    }
                }
            }
        }

        stage('Docker Build') {
            steps {
                container('docker') {
                    sh '''
                        until docker info > /dev/null 2>&1; do echo "Waiting for Docker daemon..."; sleep 2; done
                        docker build -t mi-app:latest .
                    '''
                }
            }
        }

        stage('Trivy Scan') {
            steps {
                container('trivy') {
                    sh 'trivy clean --java-db && trivy image --timeout 15m --exit-code 1 --severity CRITICAL --ignorefile .trivyignore mi-app:latest'
                }
            }
            post {
                always {
                    container('trivy') {
                        sh 'trivy image --timeout 15m --severity CRITICAL,HIGH --ignore-unfixed --format table --output trivy-report.txt mi-app:latest || true'
                    }
                    archiveArtifacts artifacts: 'trivy-report.txt', allowEmptyArchive: true
                }
            }
        }

        stage('Push Image') {
            steps {
                container('docker') {
                    withCredentials([usernamePassword(credentialsId: 'docker-registry', usernameVariable: 'REGISTRY_USERNAME', passwordVariable: 'REGISTRY_PASSWORD')]) {
                        sh '''
                            docker login -u $REGISTRY_USERNAME -p $REGISTRY_PASSWORD
                            docker tag mi-app:latest $REGISTRY_USERNAME/mi-app:${BUILD_NUMBER}
                            docker tag mi-app:latest $REGISTRY_USERNAME/mi-app:latest
                            docker push $REGISTRY_USERNAME/mi-app:${BUILD_NUMBER}
                            docker push $REGISTRY_USERNAME/mi-app:latest
                        '''
                    }
                }
            }
        }

        stage('Deploy') {
            steps {
                container('kubectl') {
                    withCredentials([usernamePassword(credentialsId: 'docker-registry', usernameVariable: 'REGISTRY_USERNAME', passwordVariable: 'REGISTRY_PASSWORD')]) {
                        sh '''
                            export APP_NAME=mi-app
                            export ENV=production
                            export BUILD_VERSION=${BUILD_NUMBER}
                            cat k8s-config/deployment.tmpl.yml | \
                                sed "s|\$APP_NAME|${APP_NAME}|g; s|\$ENV|${ENV}|g; s|\$BUILD_VERSION|${BUILD_VERSION}|g; s|\$REGISTRY_USERNAME|${REGISTRY_USERNAME}|g" | \
                                kubectl apply -f -
                            kubectl rollout status deployment/${APP_NAME}-deployment --timeout=5m
                            echo "App available at: http://$(kubectl get nodes -o jsonpath='{.items[0].status.addresses[?(@.type==\"InternalIP\")].address}'):30080"
                        '''
                    }
                }
            }
            post {
                failure {
                    container('kubectl') {
                        sh '''
                            echo "=== Deployment status ==="
                            kubectl get deployment mi-app-deployment || true
                            echo "=== Pod status ==="
                            kubectl get pods -l application=mi-app || true
                            echo "=== Pod logs ==="
                            kubectl logs -l application=mi-app --tail=50 || true
                        '''
                    }
                }
            }
        }
    }

    post {
        always {
            cleanWs()
        }
        success { 
            echo 'Pipeline successful - Application deployed at http://localhost:80'
        }
        failure { 
            echo 'Pipeline failed. Review logs above for details.'
        }
    }
}
