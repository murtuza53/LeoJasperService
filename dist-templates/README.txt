LeoJasperService 1.0.0
=======================

Self-contained bundle. No JDK, no Maven, no install — just extract and run.

Quick start
-----------
1. Double-click run.bat (or run from a console).
2. Wait for "Started LeoJasperApplication" in the log.
3. Verify: open http://localhost:8080/actuator/health — it should say {"status":"UP"}.
4. Drop your .jrxml templates into the templates\ folder; they will appear at
   GET http://localhost:8080/api/templates without restarting.

Layout
------
  jre\            Bundled, stripped JRE (built from the source JDK).
  app\app.jar     Spring Boot REST service (port 8080).
  app\cli.jar     Standalone CLI — see below.
  templates\      Drop your *.jrxml here; the registry watches this dir.
  run.bat         Start the REST service.
  README.txt      This file.

Configuration
-------------
The service reads application.yml from inside the jar. To override anything,
set environment variables before running:

  set "SERVER_PORT=9090"
  set "LEOJASPER_TEMPLATES_PATH=C:\some\other\path"
  set "JAVA_OPTS=-Xms512m -Xmx2g"
  run.bat

Or pass Spring Boot --flags directly:
  run.bat --server.port=9090 --leojasper.templates.path=D:\templates

Standalone CLI
--------------
  jre\bin\java -jar app\cli.jar ^
      --template templates\sample.jrxml ^
      --data data\items.json ^
      --input-format  json ^
      --output-format pdf ^
      --out report.pdf

Run as a Windows service
------------------------
The bundle does not install anything. To run it as a service, wrap run.bat
with NSSM (https://nssm.cc/) or use jpackage's --win-service mode. See the
project README's "Production deployment" section.

Health & metrics
----------------
  GET /actuator/health        liveness probe
  GET /actuator/metrics       JVM + report metrics
  GET /actuator/prometheus    Prometheus scrape endpoint
