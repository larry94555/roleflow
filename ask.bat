@echo off
setlocal
if "%~1"=="" (
  echo Usage: ask.bat "your prompt" ["optional system prompt"]
  exit /b 1
)
set "ROLEFLOW_PROMPT=%~1"
set "ROLEFLOW_SYSTEM=%~2"
powershell -NoProfile -Command "$payload = @{ prompt = $env:ROLEFLOW_PROMPT }; if ($env:ROLEFLOW_SYSTEM) { $payload.system = $env:ROLEFLOW_SYSTEM }; $body = $payload | ConvertTo-Json -Compress; try { (Invoke-RestMethod -Method Post -Uri 'http://localhost:8080/ask' -ContentType 'application/json' -Body $body).response } catch { Write-Error $_; exit 1 }"
endlocal
