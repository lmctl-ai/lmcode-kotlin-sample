$ErrorActionPreference = "Stop"
$classpath = "target/lmcode-kotlin-sample.jar"
$argsNative = @("-H:+UnlockExperimentalVMOptions", "-J-Xmx4g", "-H:NumberOfThreads=2", "--no-fallback", "-march=compatibility", "-H:+ReportExceptionStackTraces", "-H:IncludeResources=sample\.dsl\.json", "-H:IncludeResources=sample-version\.txt", "-cp", $classpath, "io.lmctl.lmcode.sample.Main", "build/lmcode-kotlin-sample")
$nativeImage = Join-Path $env:JAVA_HOME "bin/native-image.cmd"
if (!(Test-Path $nativeImage)) { throw "GraalVM native-image.cmd is required" }
& $nativeImage @argsNative
if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
