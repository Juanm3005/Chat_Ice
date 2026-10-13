# Respuestas técnicas

## 1. Arquitectura híbrida TCP/UDP

Se utiliza **ZeroC Ice sobre TCP** para la autenticación, la mensajería, el control de salas y la transferencia de archivos, porque estos datos necesitan llegar completos y en orden. Para la voz se utiliza **UDP**, ya que permite transmitir audio con menor latencia, sin esperar retransmisiones de paquetes perdidos.

Si el audio se transmitiera por TCP, la pérdida de un segmento podría retrasar los siguientes datos hasta que se recuperara el faltante. Esto provocaría pausas, cortes y variaciones en el sonido, afectando la comunicación en tiempo real.

## 2. Manejo de buffers y fragmentación en Ice

Enviar un archivo de 80 MB mediante una sola llamada RPC puede superar el límite de tamaño de mensaje de Ice. En Ice 3.7, `Ice.MessageSizeMax` tiene un valor predeterminado de 1024 KiB (1 MiB), por lo que un mensaje demasiado grande puede rechazarse con una `MarshalException` en Java.

Para evitarlo, el proyecto divide los archivos en bloques de **32 KiB**, que se envían mediante `writeChunk` y se reciben mediante `readChunk`. Así se reduce el consumo de memoria y se mantiene cada mensaje por debajo del límite. El parámetro principal es `Ice.MessageSizeMax`, mientras que `Ice.TCP.SndSize` y `Ice.TCP.RcvSize` permiten ajustar los buffers de red, pero no sustituyen ese límite.

## 3. Escalabilidad en llamadas grupales: Relay frente a Mesh

En una arquitectura **Mesh**, cada cliente envía un paquete de audio a los otros \(N-1\) participantes. Por tanto, cada cliente emite \(N-1\) paquetes y el servidor no procesa el audio. Su principal desventaja es que aumenta el consumo de subida y la cantidad de conexiones de cada usuario.

En una arquitectura **Relay**, cada cliente envía un paquete al servidor, que lo reenvía a los otros \(N-1\) participantes. Cada cliente emite un paquete, mientras que el servidor recibe \(N\) paquetes y reenvía \(N(N-1)\) paquetes por intervalo.

En conclusión, Mesh distribuye el tráfico entre los clientes, mientras que Relay reduce la carga de subida individual, pero exige más ancho de banda y capacidad al servidor. Ambas alternativas generan un tráfico total de orden \(O(N^2)\) cuando todos los participantes hablan simultáneamente.

**Alcance del proyecto:** el código implementa llamadas punto a punto, no llamadas grupales. Por eso, esta comparación describe las dos alternativas planteadas por el taller para ampliar el sistema.

## 4. Transparencia y callbacks en Ice

Los callbacks permiten que el servidor notifique eventos al cliente sin que este tenga que consultar constantemente si hay novedades.

El cliente crea un `ObjectAdapter` sin endpoints, registra los objetos de callback y lo asocia a la conexión Ice existente mediante `setAdapter(adapter)`. Luego, envía los proxies al servidor para que este pueda invocarlos cuando ocurran eventos. Al cerrar sesión, se deben desregistrar los callbacks y liberar los recursos.

Un **proxy directo** contiene los endpoints necesarios para localizar un objeto remoto y normalmente requiere que este sea accesible por la red. Un **proxy indirecto** identifica el objeto y su adaptador, y utiliza un servicio de localización para encontrarlo. En este proyecto, los callbacks aprovechan la conexión existente mediante `ice_fixed(current.con)`, evitando que el cliente necesite un puerto independiente para recibir las notificaciones.