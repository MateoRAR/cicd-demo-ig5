
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

### 4. Static Analysis (SonarQube)
Ejecuta análisis estático de código usando SonarQube:
- Se conecta a SonarQube usando token de autenticación
- Analiza el código en busca de bugs, vulnerabilidades y code smells
- Espera a que el análisis termine antes de continuar

### 5. Quality Gate
Verifica las puertas de calidad:
- **Quality Gate**: El proyecto debe pasar las condiciones de calidad configuradas
- **Security Hotspots**: El pipeline falla si hay Security Hotspots sin revisar
- Si no pasa estas validaciones, el pipeline se detiene

### 6. Docker Build
Construye la imagen Docker:
```bash
docker build -t mi-app:latest .
```

### 7. Install Trivy
Instala Trivy para escaneo de vulnerabilidades en la imagen.

### 8. Container Security Scan (Trivy)
Escanea la imagen Docker en busca de vulnerabilidades:
- Falla si encuentra vulnerabilidades de nivel **CRITICAL**
- Genera reporte con vulnerabilidades HIGH y CRITICAL

### 9. Deploy
Despliega la aplicación localmente (solo en ramas master/main):
```bash
docker run -d --name mi-app --restart=unless-stopped -p 80:80 mi-app:latest
```

## Puertas de Calidad (Gatekeeping)

El pipeline fallará automáticamente si:
1. SonarQube detecta un "Security Hotspot" sin revisar
2. Trivy encuentra vulnerabilidades de nivel "CRITICAL" en la imagen

## Limpieza e Infraestructura

El bloque `post` del pipeline:
- **always**: Limpia contenedores, imágenes y el workspace
- **success**: Notifica despliegue exitoso
- **failure**: Muestra logs de error y reporte de Trivy
- **unstable**: Notifica alertas de calidad

## Requisitos Previos

1. **Kind Cluster** (Kubernetes en Docker)
2. **Jenkins** desplegado en Kubernetes
3. **SonarQube** ejecutándose (accesible en `http://sonarqube-sonarqube:9000`)
4. **Docker** configurado en los nodos
5. **Credenciales configuradas en Jenkins**:
   - `sonarqube-token`: Token de autenticación de SonarQube

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

Las pruebas se separan usando [JUnit Categories][].

[JUnit Categories]: https://maven.apache.org/surefire/maven-surefire-plugin/examples/junit.html

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

1. Haz un cambio en el código (ej. `src/main/resources/templates/index.html`)
2. Commit y push al repositorio
3. Jenkins detectará el cambio automáticamente
4. El pipeline ejecutará: build → test → análisis → escaneo → despliegue
5. Si pasa todas las validaciones, se desplegará la nueva versión automáticamente

### Ejemplo de cambio para probar:
```bash
# Editar el archivo para simular un cambio
echo "<h1>Nueva versión desplegada!</h1>" > src/main/resources/templates/index.html
git add .
git commit -m "Actualización de prueba"
git push origin master
```

## Visualización de Resultados de Trivy

Para ver la salida del escaneo de Trivy:

1. **Logs del pipeline**: La salida completa del comando `trivy image` se muestra en la etapa "Container Security Scan (Trivy)" de los logs del build en Jenkins.
2. **Artefactos del build**: El reporte `trivy-report.txt` (con vulnerabilidades CRITICAL y HIGH) se archiva automáticamente y está disponible en la página del build → pestaña "Artifacts".
3. **Notificaciones de fallo**: Si el pipeline falla por vulnerabilidades CRITICAL, el mensaje de error indica revisar `trivy-report.txt`.

## Troubleshooting

### Error: "Insufficient privileges" en SonarQube
- Verifica que el token en Jenkins (`sonarqube-token`) tenga permisos en el proyecto
- Asegúrate de que el proyecto `my-app` exista en SonarQube o se cree automáticamente
- El token debe tener permisos de `Execute Analysis`

### Error en Hotspots API
- El pipeline ahora maneja respuestas null de la API de hotspots
- Si el proyecto es nuevo, puede tomar tiempo en aparecer en SonarQube