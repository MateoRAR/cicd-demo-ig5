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
                  - name: docker
                    image: docker:latest
                    command:
                    - sleep
                    args:
                    - infinity
                    volumeMounts:
                    - name: docker-sock
                      mountPath: /var/run/docker.sock
                  volumes:
                  - name: docker-sock
                    hostPath:
                      path: /var/run/docker.sock
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
            steps { container('maven') { sh 'mvn test -Djacoco.skip=true' } }
            post { always { junit allowEmptyResults: true, testResults: 'target/surefire-reports/*.xml' } }
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
                                -Dsonar.qualitygate.timeout=120
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

                            # Verificar Quality Gate
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

                            # Security Hotspots
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
            steps { container('docker') { sh 'docker build -t mi-app:latest .' } }
        }

        stage('Trivy Scan') {
            steps {
                container('docker') {
                    sh '''
                        curl -sfL https://raw.githubusercontent.com/aquasecurity/trivy/main/contrib/install.sh | sh -s -- -b /usr/local/bin v0.50.0
                        trivy image --exit-code 1 --severity CRITICAL mi-app:latest
                    '''
                }
            }
            post {
                always {
                    sh 'trivy image --severity CRITICAL,HIGH --format table --output trivy-report.txt mi-app:latest || true'
                    archiveArtifacts artifacts: 'trivy-report.txt', allowEmptyArchive: true
                }
            }
        }

        stage('Deploy') {
            when { branch 'master' }
            steps {
                container('docker') {
                    sh '''
                        docker stop mi-app || true; docker rm mi-app || true
                        docker run -d --name mi-app -p 80:80 mi-app:latest
                    '''
                }
            }
        }
    }

    post {
        always {
            cleanWs()
        }
        success { echo 'Pipeline exitoso - App en http://localhost:80' }
        failure { echo 'Pipeline fallido. Revisa trivy-report.txt y logs de SonarQube.' }
    }
}
