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
                    withSonarQubeEnv('SonarQube') {
                        sh 'mvn sonar:sonar -Dsonar.projectKey=my-app'
                    }
                }
            }
        }

        stage('Quality Gate') {
            steps {
                timeout(time: 5, unit: 'MINUTES') {
                    waitForQualityGate abortPipeline: true
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
