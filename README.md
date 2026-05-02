
# CICD-DEMO

Proyecto de demostración de CI/CD con Spring Boot, Jenkins, SonarQube y Trivy.

## Topology

CICD Demo usa Kubernetes para el despliegue:

* Deployment
* Services
* Ingress (con TLS)

```bash
     internet
        |
   [ Ingress ]
   --|-----|--
   [ Services ]
   --|-----|--
   [   Pods   ]
```

## Componentes del Proyecto

* Spring Boot Java app
* Jenkinsfile con pipeline completo
* Dockerfile para la aplicación
* Makefile y docker-compose
* Despliegue en Kubernetes

## Flujo CI/CD (Pipeline)

El pipeline está configurado en el `Jenkinsfile` y consta de las siguientes etapas:

### 1. Checkout
Descarga el código fuente del repositorio.

### 2. Build
Compila el proyecto con Maven:
```bash
mvn clean package -DskipTests
```

### 3. Test
Ejecuta las pruebas unitarias:
```bash
mvn test -Djacoco.skip=true
```

### 4. Jacoco Report
Genera reporte de cobertura de código.

### 5. SonarQube Analysis
Ejecuta análisis estático de código usando SonarQube:
- Se conecta a SonarQube usando token de autenticación
- Analiza el código en busca de bugs, vulnerabilidades y code smells
- Espera a que el análisis termine antes de continuar

### 6. Quality Gate & Hotspots
Verifica las puertas de calidad:
- Quality Gate: El proyecto debe pasar las condiciones de calidad configuradas
- Security Hotspots: El pipeline falla si hay Security Hotspots sin revisar

### 7. Docker Build
Construye la imagen Docker:
```bash
docker build -t mi-app:latest .
```

### 8. Trivy Scan
Escanea la imagen Docker en busca de vulnerabilidades:
- Falla si encuentra vulnerabilidades de nivel CRITICAL
- Genera reporte con vulnerabilidades CRITICAL y HIGH

### 9. Deploy
Despliega la aplicación en el cluster local de Kubernetes usando Docker:

```bash
docker run -d \
    --name mi-app \
    -p 80:80 \
    --restart=unless-stopped \
    mi-app:latest
```

El stage Deploy realiza las siguientes operaciones:
- Espera a que el Docker daemon esté listo
- Limpia cualquier contenedor anterior llamado mi-app
- Crea el contenedor nuevo con mapeo de puerto 80:80
- Establece la política de reinicio automático
- Valida que el contenedor esté ejecutándose después de 3 segundos
- Si hay error, genera un reporte de diagnóstico

Para acceder a la aplicación desplegada:
```bash
curl http://localhost:80
# O en el navegador: http://localhost:80
```

## Puertas de Calidad (Gatekeeping)

El pipeline fallará automáticamente si:
1. SonarQube detecta un "Security Hotspot" sin revisar
2. Trivy encuentra vulnerabilidades de nivel "CRITICAL" en la imagen

## Limpieza e Infraestructura

El pipeline asegura que los contenedores se mantengan en un estado consistente mediante el bloque post en el Jenkinsfile.

### Limpieza Pre-Deploy

Antes de desplegar una nueva versión:
1. Detiene el contenedor anterior si existe: `docker stop mi-app`
2. Elimina el contenedor anterior: `docker rm mi-app`
3. Espera 1 segundo para liberar los puertos

### Limpieza Post-Pipeline

El bloque post { } en el Jenkinsfile ejecuta las siguientes acciones:

1. **always (Siempre)**
   - Se ejecuta independientemente del resultado del pipeline
   - Ejecuta cleanWs() para limpiar el workspace de Jenkins
   - Garantiza que los recursos se liberen correctamente

2. **failure (Si falla)**
   - Se ejecuta solo si el pipeline ha fallado
   - Captura información de diagnóstico de Docker
   - Muestra estado de contenedores en ejecución
   - Muestra logs del contenedor mi-app
   - Muestra estado de imágenes Docker
   - Preserva la información para análisis de problemas

3. **success (Si tiene éxito)**
   - Se ejecuta solo si el pipeline fue exitoso
   - Notifica que la aplicación está disponible en http://localhost:80

### Garantías de Estado Consistente

El pipeline asegura que:
- No hay contenedores mi-app duplicados entre builds
- Los puertos se liberan correctamente antes de reutilizar
- El workspace de Jenkins se limpia entre builds
- Los logs están disponibles para auditoría
- El contenedor tiene --restart=unless-stopped para resiliencia

### Manejo de Errores en Deploy

Si el stage Deploy falla, el bloque post { failure { ... } } captura automáticamente:
1. Estado de todos los contenedores (docker ps -a)
2. Logs del contenedor mi-app (docker logs)
3. Estado de las imágenes Docker (docker images)

Esta información se muestra en los logs del pipeline para facilitar el debugging.

### Comandos de Recuperación Manual

Si necesitas intervenir manualmente:

```bash
# Ver todos los contenedores
docker ps -a

# Ver logs del contenedor
docker logs mi-app

# Detener contenedor
docker stop mi-app || true

# Eliminar contenedor
docker rm mi-app || true

# Limpiar imágenes
docker image prune -f

# Redeploy manual
docker run -d --name mi-app -p 80:80 --restart=unless-stopped mi-app:latest
```

## Requisitos Previos

1. Kind Cluster (Kubernetes en Docker)
2. Jenkins desplegado en Kubernetes
3. SonarQube ejecutándose (accesible en http://sonarqube-sonarqube:9000)
4. Docker configurado en los nodos
5. Credenciales configuradas en Jenkins:
   - sonarqube-token: Token de autenticación de SonarQube
   - sonarqube-user-token: Token adicional para consultas a la API

## Cómo ejecutar la aplicación localmente

```bash
make
```

O con Docker:
```bash
docker build -t mi-app:latest .
docker run -d -p 80:80 mi-app:latest
```

## Testing

Las pruebas se separan usando JUnit Categories.

### Unit Tests
```bash
mvn test -Dgroups=UnitTest
```

### Integration Tests
```bash
mvn integration-test -Dgroups=IntegrationTests
```

## Prueba del Pipeline

Para probar el pipeline completo:

1. Haz un cambio en el código (ej. src/main/resources/templates/index.html)
2. Commit y push al repositorio
3. Jenkins detectará el cambio automáticamente
4. El pipeline ejecutará: build -> test -> análisis -> escaneo -> deploy
5. Si pasa todas las validaciones, se desplegará la nueva versión automáticamente

Ejemplo de cambio para probar:
```bash
echo "<h1>Nueva versión desplegada!</h1>" > src/main/resources/templates/index.html
git add .
git commit -m "Actualización de prueba"
git push origin master
```

## Visualización de Resultados de Trivy

Para ver la salida del escaneo de Trivy:

1. Logs del pipeline: La salida completa del comando trivy image se muestra en la etapa "Trivy Scan" de los logs del build en Jenkins.
2. Artefactos del build: El reporte trivy-report.txt se archiva automáticamente y está disponible en la página del build -> pestaña Artifacts.
3. Notificaciones de fallo: Si el pipeline falla por vulnerabilidades CRITICAL, el mensaje de error indica revisar trivy-report.txt.

## Troubleshooting

### Error: "Docker daemon no disponible"

Síntoma: Cannot connect to Docker daemon

Causa: El contenedor dind (Docker in Docker) en el pod de Jenkins no está listo

Solución:
1. El Jenkinsfile espera automáticamente con until docker info > /dev/null
2. Si sigue fallando, verifica el estado del pod:
```bash
kubectl get pods -n jenkins
kubectl describe pod <jenkins-pod-name> -n jenkins
```

### Error: "Puerto 80 ya está en uso"

Síntoma: bind: address already in use

Causa: Existe un contenedor mi-app anterior o algo más en el puerto 80

Solución:
```bash
# Ver qué está usando el puerto 80
docker ps -a | grep mi-app

# O lista todo lo que usa el puerto
docker ps -a --format "table {{.Names}}\t{{.Ports}}"

# Detener y eliminar
docker stop mi-app || true
docker rm mi-app || true

# Limpiar completamente
docker container prune -f
```

### Error: "Contenedor se crea pero no está accesible"

Síntoma: docker ps muestra mi-app corriendo pero curl localhost falla

Verificación:
```bash
# Ver logs de la app
docker logs mi-app

# Verificar puerto mapeado
docker port mi-app

# Probar conectividad interna
docker exec mi-app curl http://localhost:8080

# Verificar que la app inicia en el puerto correcto
# El Dockerfile debe exponer el puerto 8080
# La app debe escuchar en 0.0.0.0:8080, no solo en localhost
```

### Error: "Hotspots API error" en SonarQube

Síntoma: Pipeline falla en Quality Gate & Hotspots

Causa: El proyecto es nuevo o el token no tiene permisos suficientes

Solución:
```bash
# Verifica el token en Jenkins
# Settings > Manage Credentials > sonarqube-token

# El token debe tener estos permisos:
# - Execute Analysis
# - Browse
# - See Source Code
```

### Error: "Insufficient privileges" en SonarQube

Síntoma: 401 Unauthorized al conectar con SonarQube

Solución:
1. Verifica que el token existe en Jenkins:
   En Jenkins UI: Manage Jenkins > Credentials > Stores scoped to Jenkins
   Busca sonarqube-token o sonarqube-user-token

2. Verifica que el token es válido en SonarQube:
   En SonarQube: cuenta > security > tokens
   Regenera si es necesario

3. Crea el proyecto en SonarQube si no existe:
   SonarQube UI > Projects > Create Project
   Project Key: my-app
   Project Name: my-app
