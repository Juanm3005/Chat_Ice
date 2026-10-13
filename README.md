## Requisitos

- Windows 10/11.
- JDK 17. Configura `JAVA_HOME` y `PATH` para que apunten al JDK 17, no solo a un runtime de Java.
- ZeroC Ice para Java 3.7.x instalado localmente, con `slice2java` disponible en `PATH`. Gradle ejecuta ese compilador para generar las clases Java a partir de `common/src/main/slice/Chat.ice`.
- Acceso a Maven Central la primera vez que se compila, para descargar Gradle/ZeroC Ice y sus dependencias.

Verifica las herramientas desde PowerShell:

```powershell
java -version
.\gradlew.bat --version
Get-Command slice2java
```

## Compilar y ejecutar

Abre una terminal PowerShell en la carpeta raiz del repositorio. Inicia primero el servidor:

```powershell
.\gradlew.bat runServer
```

Deja esa terminal abierta. En una segunda terminal, en la misma carpeta, inicia un cliente:

```powershell
.\gradlew.bat runClient
```

## Comandos del chat

| Comando | Funcion |
| --- | --- |
| `/help` | Muestra la ayuda en el cliente. |
| `/users` | Lista los usuarios conectados. |
| `/msg <usuario> [texto]` | Abre una conversacion privada y, opcionalmente, envia un primer mensaje. |
| `/rooms` | Lista las salas disponibles. |
| `/create <sala>` | Crea una sala y entra en ella. El nombre acepta letras, numeros, `_` y `-` (hasta 30 caracteres). |
| `/join <sala>` | Se une a una sala existente. |
| `/room <sala>` | Abre una sala a la que ya perteneces. |
| `/members [sala]` | Muestra los miembros de una sala; sin argumento usa la sala activa. |
| `/leave [sala]` | Abandona una sala; sin argumento usa la sala activa. |
| `/closew` o `/general` | Vuelve al chat general sin abandonar una sala. |
| `/upload <ruta>` | Comparte un archivo en el chat general, la conversacion privada o la sala activa. |
| `/download <archivo>` | Descarga un archivo de la conversacion activa en `downloads`. |
| `/call <usuario>` | Inicia una llamada de voz. |
| `/accept`, `/reject` | Acepta o rechaza una llamada entrante. |
| `/hangup` | Termina la llamada activa o cancela la llamada saliente. |
| `/exit` | Cierra la sesion y sale del cliente. |

## Estructura

- `common`: contrato Slice y clases compartidas de ZeroC Ice.
- `server`: servidor Ice, salas, mensajes, archivos y senalizacion de llamadas.
- `client`: cliente interactivo de consola, callbacks y audio UDP.

## Integrantes

- Juan José Rojas Correa
- Nicholas Solis Pinilla
- Juan Camilo Peleaz
- Laura Andrea Cárdenas Zuleta