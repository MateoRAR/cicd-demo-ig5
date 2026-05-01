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
                      requests:
                        memory: "1Gi"
                        cpu: "500m"
                    env:
                    - name: MAVEN_OPTS
                      value: "-Xmx512m -Xms256m"
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

    environment {
        SONAR_HOST_URL = 'http://sonarqube-sonarqube:9000'
    }

    stages {
        stage('Checkout') {
            steps { checkout scm }
        }

        stage('Build') {
            steps {
                container('maven') {
                    sh 'mvn clean package -DskipTests'
                }
            }
        }

        stage('Test') {
            steps {
                container('maven') {
                    sh 'mvn test -Djacoco.skip=true'
                }
            }
            post {
                always {
                    junit allowEmptyResults: true, testResults: 'target/surefire-reports/*.xml'
                }
            }
        }

        stage('Static Analysis (SonarQube)') {
            steps {
                container('maven') {
                    withCredentials([string(credentialsId: 'sonarqube-token', variable: 'SONAR_TOKEN')]) {
                        sh '''
                            mvn sonar:sonar \
                                -Dsonar.host.url=$SONAR_HOST_URL \
                                -Dsonar.projectKey=my-app \
                                -Dsonar.login=$SONAR_TOKEN
                        '''
                    }
                }
            }
        }

        stage('Quality Gate') {
            steps {
                container('docker') {
                    withCredentials([string(credentialsId: 'sonarqube-token', variable: 'SONAR_TOKEN')]) {
                        sh '''
                            set -e
                            apk add --no-cache curl jq > /dev/null 2>&1

                            # Esperar a que el análisis termine
                            TIMEOUT=120
                            ELAPSED=0
                            ANALYSIS_DONE=false
                            while [ $ELAPSED -lt $TIMEOUT ]; do
                                CE_RESPONSE=$(curl -s -u $SONAR_TOKEN: "http://sonarqube-sonarqube:9000/api/ce/component?component=my-app" 2>/dev/null || echo '{}')
                                STATUS=$(echo "$CE_RESPONSE" | jq -r '.task.status // empty' 2>/dev/null || echo "")

                                if [ "$STATUS" = "SUCCESS" ]; then
                                    echo "Análisis completado correctamente"
                                    ANALYSIS_DONE=true
                                    break
                                elif [ "$STATUS" = "FAILED" ]; then
                                    echo "Análisis de SonarQube fallido"
                                    exit 1
                                elif [ "$STATUS" = "PENDING" ] || [ "$STATUS" = "IN_PROGRESS" ]; then
                                    echo "Esperando análisis de SonarQube... ($STATUS)"
                                fi
                                sleep 5
                                ELAPSED=$((ELAPSED + 5))
                            done

                            if [ "$ANALYSIS_DONE" = false ]; then
                                echo "Timeout o sin tarea activa. Procediendo con verificación..."
                            fi

                            # Verificar Quality Gate
                            QG_RESPONSE=$(curl -s -u $SONAR_TOKEN: "http://sonarqube-sonarqube:9000/api/qualitygates/project_status?projectKey=my-app")
                            QG_STATUS=$(echo "$QG_RESPONSE" | jq -r '.projectStatus.status')
                            echo "Quality Gate Status: $QG_STATUS"

                            if [ "$QG_STATUS" != "OK" ]; then
                                echo "Quality Gate FAILED!"
                                echo "$QG_RESPONSE" | jq .
                                exit 1
                            fi

                            # Verificar Security Hotspots
                            HOTSPOT_RESPONSE=$(curl -s -u $SONAR_TOKEN: "http://sonarqube-sonarqube:9000/api/hotspots/search?projectKey=my-app")
                            HOTSPOTS=$(echo "$HOTSPOT_RESPONSE" | jq '[.hotspots[] | select(.status != "REVIEWED")] | length')
                            echo "Security Hotspots sin revisar: $HOTSPOTS"

                            if [ "$HOTSPOTS" -gt 0 ]; then
                                echo "Security Hotspots detectados! Pipeline fallido."
                                echo "$HOTSPOT_RESPONSE" | jq .
                                exit 1
                            fi

                            echo "Quality Gate y Security Hotspots: OK"
                        '''
                    }
                }
            }
        }

        stage('Docker Build') {
            steps {
                container('docker') {
                    sh 'docker build -t mi-app:latest .'
                }
            }
        }

        stage('Install Trivy') {
            steps {
                container('docker') {
                    sh '''
                        apk add --no-cache curl tar
                        curl -sfL https://raw.githubusercontent.com/aquasecurity/trivy/main/contrib/install.sh | sh -s -- -b /usr/local/bin v0.50.0
                        trivy --version
                    '''
                }
            }
        }

        stage('Container Security Scan (Trivy)') {
            steps {
                container('docker') {
                    sh '''
                        trivy image --exit-code 1 --severity CRITICAL mi-app:latest
                    '''
                }
            }
            post {
                always {
                    script {
                        sh 'trivy image --severity CRITICAL,HIGH --format table --output trivy-report.txt mi-app:latest || true'
                        archiveArtifacts artifacts: 'trivy-report.txt', allowEmptyArchive: true
                    }
                }
            }
        }

        stage('Deploy') {
            when { branch 'master' }
            steps {
                container('docker') {
                    sh '''
                        docker stop mi-app || true
                        docker rm mi-app || true
                        docker run -d --name mi-app -p 80:80 mi-app:latest
                    '''
                }
            }
        }
    }

    post {
        always {
            echo '--- Limpieza post-pipeline ---'
            container('docker') {
                sh 'docker stop mi-app || true'
                sh 'docker rm mi-app || true'
                sh 'docker rmi mi-app:latest || true'
            }
            cleanWs()
        }
        success {
            echo 'Pipeline completado exitosamente.'
        }
        failure {
            echo 'Pipeline fallido. Revisa trivy-report.txt y logs de SonarQube.'
        }
    }
}
