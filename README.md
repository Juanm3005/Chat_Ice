# Chat distribuido con ZeroC Ice

Aplicacion de consola para mensajeria distribuida, salas, transferencia de archivos y llamadas de voz entre clientes. La comunicacion de chat y senalizacion se implementa con ZeroC Ice; el audio de las llamadas se transmite directamente entre clientes mediante UDP.

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

Escribe un nickname cuando se solicite. Puedes abrir mas clientes en terminales adicionales, usando un nickname distinto para cada uno. El servidor escucha en el puerto TCP `10000`. Para detener un cliente, escribe `/exit`; para detener el servidor, usa `Ctrl+C`.

Por defecto, el cliente se conecta a `127.0.0.1`. Para conectarlo a un servidor en otra maquina:

```powershell
.\gradlew.bat :client:run --args="DIRECCION_IP_DEL_SERVIDOR"
```

En ese caso, el puerto TCP `10000` debe estar accesible desde los clientes. Las llamadas tambien requieren que los clientes puedan intercambiar trafico UDP directamente; firewalls o NAT pueden impedir el audio aun cuando el chat funcione.

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

Tambien puedes escribir texto sin comando para enviarlo al chat general o a la conversacion privada/sala activa. Para adjuntar un archivo, usa una ruta local, por ejemplo:

```text
/upload C:\Users\Nombre\Documents\informe.pdf
```

Al ejecutar con Gradle, los archivos recibidos se guardan en `client/downloads` y los archivos del servidor en `server/chat_files`. Las rutas relativas de `/upload` se resuelven desde la carpeta `client`; por eso se recomienda usar una ruta absoluta, como en el ejemplo. Los archivos compartidos por sala se mantienen separados por sala; los archivos privados se almacenan por transferencia y solo quedan asociados al remitente y al destinatario.

## Compilacion y pruebas

```powershell
.\gradlew.bat test
```

El proyecto no contiene pruebas automatizadas todavia; esta tarea comprueba la compilacion de los modulos y la generacion de codigo Slice. La tarea `compileSlice` necesita `slice2java` en `PATH` y regenera las clases Java del contrato desde el archivo Slice. Despues de ejecutar la compilacion, puedes comprobar los cambios locales con:

```powershell
git status --short
```

## Verificacion manual recomendada

1. Inicia un servidor y dos clientes con nicknames diferentes.
2. En un cliente, usa `/users` y envia un mensaje de texto; confirma que aparece en el otro.
3. Prueba `/msg <otro_usuario> hola` y verifica que el mensaje privado solo se muestra al destinatario.
4. Crea una sala con `/create equipo`, comprueba `/rooms`, y desde el otro cliente usa `/join equipo`. Envia mensajes en la sala y revisa `/members`.
5. Sube un archivo con `/upload <ruta>` y descargalo con `/download <nombre>` desde la conversacion correspondiente.
6. Prueba la llamada con `/call <otro_usuario>` y acepta con `/accept` en el segundo cliente. Verifica que ambos equipos permitan el uso del microfono, los parlantes y el trafico UDP.
7. Sal con `/exit` y confirma que el usuario ya no aparece en `/users`.

## Estructura

- `common`: contrato Slice y clases compartidas de ZeroC Ice.
- `server`: servidor Ice, salas, mensajes, archivos y senalizacion de llamadas.
- `client`: cliente interactivo de consola, callbacks y audio UDP.

## Integrantes

- Juan José Rojas Correa
- Nicholas Solis Pinilla
- Juan Camilo Peleaz
- Laura Andrea Cárdenas Zuleta
