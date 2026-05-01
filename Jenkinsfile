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
                                -Dsonar.token=$SONAR_TOKEN
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
                            apk add --no-cache curl jq > /dev/null 2>&1

                            # Esperar a que el análisis termine
                            TIMEOUT=120
                            ELAPSED=0
                            while [ $ELAPSED -lt $TIMEOUT ]; do
                                RESPONSE=$(curl -s -H "Authorization: Bearer $SONAR_TOKEN" "$SONAR_HOST_URL/api/ce/component?component=my-app")
                                echo "Respuesta SonarQube: $RESPONSE"

                                # Verificar si hay errores de autenticación
                                if echo "$RESPONSE" | jq -e '.errors' > /dev/null 2>&1; then
                                    ERROR_MSG=$(echo "$RESPONSE" | jq -r '.errors[0].msg // "Unknown error"')
                                    echo "Error: $ERROR_MSG"

                                    if echo "$ERROR_MSG" | grep -qi "insufficient privileges\|unauthorized\|forbidden"; then
                                        echo "Esperando permisos o creación del proyecto..."
                                        sleep 10
                                        ELAPSED=$((ELAPSED + 10))
                                        continue
                                    fi
                                    exit 1
                                fi

                                STATUS=$(echo "$RESPONSE" | jq -r '.current.status // empty' 2>/dev/null)
                                if [ "$STATUS" = "SUCCESS" ]; then
                                    echo "Análisis completado"
                                    break
                                elif [ "$STATUS" = "FAILED" ]; then
                                    echo "Análisis de SonarQube fallido"
                                    exit 1
                                fi
                                echo "Esperando análisis... ($STATUS)"
                                sleep 5
                                ELAPSED=$((ELAPSED + 5))
                            done

                            if [ $ELAPSED -ge $TIMEOUT ]; then
                                echo "Timeout esperando análisis"
                                exit 1
                            fi

                            # Quality Gate
                            QG_STATUS=$(curl -s -H "Authorization: Bearer $SONAR_TOKEN" "$SONAR_HOST_URL/api/qualitygates/project_status?projectKey=my-app" | jq -r '.projectStatus.status // "ERROR"')
                            echo "Quality Gate: $QG_STATUS"
                            if [ "$QG_STATUS" != "OK" ]; then
                                echo "Quality Gate FAILED!"
                                exit 1
                            fi

                            # Security Hotspots - manejar respuesta null
                            HOTSPOTS_RESPONSE=$(curl -s -H "Authorization: Bearer $SONAR_TOKEN" "$SONAR_HOST_URL/api/hotspots/search?projectKey=my-app")
                            echo "Hotspots response: $HOTSPOTS_RESPONSE"

                            # Verificar si la respuesta tiene hotspots
                            if echo "$HOTSPOTS_RESPONSE" | jq -e '.hotspots != null' > /dev/null 2>&1; then
                                HOTSPOTS=$(echo "$HOTSPOTS_RESPONSE" | jq '[.hotspots[] | select(.status != "REVIEWED")] | length')
                            else
                                echo "No se encontraron hotspots o el proyecto no existe aún"
                                HOTSPOTS=0
                            fi

                            echo "Security Hotspots sin revisar: $HOTSPOTS"
                            if [ "$HOTSPOTS" -gt 0 ]; then
                                echo "Security Hotspots detectados! Pipeline fallido."
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
            when {
                anyOf {
                    branch 'master'
                    branch 'main'
                }
            }
            steps {
                container('docker') {
                    sh '''
                        echo "Iniciando despliegue..."
                        docker stop mi-app || true
                        docker rm mi-app || true
                        docker run -d --name mi-app --restart=unless-stopped -p 80:80 mi-app:latest
                        sleep 3
                        if docker ps | grep -q mi-app; then
                            echo "Despliegue exitoso - contenedor mi-app ejecutándose"
                            docker logs mi-app --tail 20
                        else
                            echo "Error: el contenedor no está ejecutándose"
                            exit 1
                        fi
                    '''
                }
            }
        }
    }

    post {
        always {
            echo '--- Limpieza post-pipeline ---'
            script {
                try {
                    container('docker') {
                        sh '''
                            echo "Limpiando contenedores..."
                            docker stop mi-app || true
                            docker rm mi-app || true
                            docker rmi mi-app:latest || true
                            docker system prune -f --volumes || true
                        '''
                    }
                } catch (Exception e) {
                    echo "Error durante limpieza: ${e.getMessage()}"
                }
            }
            cleanWs()
        }
        success {
            echo 'Pipeline completado exitosamente - Aplicación desplegada en http://localhost:80'
        }
        failure {
            echo 'Pipeline fallido. Revisa trivy-report.txt y logs de SonarQube.'
            script {
                try {
                    container('docker') {
                        sh 'docker logs mi-app --tail 50 || true'
                    }
                } catch (Exception e) {
                    echo "No se pudo obtener logs del contenedor"
                }
            }
        }
        unstable {
            echo 'Pipeline inestable - revisar alertas de calidad'
        }
    }
}
