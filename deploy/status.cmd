@echo off
REM ===================================================================
REM  oceanengine 追投服务 —— 运行状态一键查看
REM
REM  注意：本文件必须保存为 ANSI/GBK 编码 + CRLF 换行。
REM        不要改成 UTF-8，也不要加 chcp 65001 —— 会导致 cmd 解析错位。
REM ===================================================================
setlocal
set BASE=D:\oceanengine
set LOGS=%BASE%\logs

echo.
echo ==================== 1. 服务状态 ====================
powershell -NoProfile -Command "$s = Get-Service -Name oceanengine -ErrorAction SilentlyContinue; if ($s) { Write-Host ('    状态: ' + $s.Status + '    启动类型: ' + $s.StartType) } else { Write-Host '    [X] 服务未安装' }"
echo.

echo ==================== 2. 进程 ====================
%SystemRoot%\System32\tasklist.exe /FI "IMAGENAME eq oceanengine-service.exe" /NH 2>nul | %SystemRoot%\System32\findstr.exe /I "oceanengine-service.exe" >nul
if errorlevel 1 (echo    [X] 包装进程未运行) else (echo    [OK] oceanengine-service.exe 在运行)
%SystemRoot%\System32\tasklist.exe /FI "IMAGENAME eq java.exe" /NH 2>nul | %SystemRoot%\System32\findstr.exe /I "java.exe" >nul
if errorlevel 1 (echo    [X] java 进程未运行) else (echo    [OK] java 进程在运行)
echo.

echo ==================== 3. 端口监听 ====================
%SystemRoot%\System32\netstat.exe -ano | %SystemRoot%\System32\findstr.exe "127.0.0.1:8080" | %SystemRoot%\System32\findstr.exe "LISTENING"
if errorlevel 1 echo    [X] 127.0.0.1:8080 未监听
echo.

echo ==================== 4. 健康检查 ====================
powershell -NoProfile -Command "try { $r = Invoke-WebRequest -UseBasicParsing 'http://127.0.0.1:8080/actuator/health' -TimeoutSec 5; $c = $r.Content; if ($c -is [byte[]]) { $c = [Text.Encoding]::UTF8.GetString($c) }; Write-Host ('    [OK] HTTP ' + [int]$r.StatusCode + '  ' + $c) } catch { $sc = ''; if ($_.Exception.Response) { $sc = [int]$_.Exception.Response.StatusCode }; Write-Host ('    [!] 健康检查异常，HTTP ' + $sc) }"
echo.

echo ==================== 5. 最近轮次（业务日志） ====================
powershell -NoProfile -Command "$p = Join-Path '%LOGS%' 'oceanengine-service.out.log'; if (Test-Path $p) { Get-Content -Path $p -Tail 500 -Encoding Default | Select-String -Pattern '^\[汇总\]','^\[轮次中止\]','^\[日报\]' | Select-Object -Last 5 | ForEach-Object { '    ' + $_.Line } } else { Write-Host '    [X] 业务日志不存在' }"
echo.

echo ==================== 6. 日志文件 ====================
if exist "%LOGS%\oceanengine-service.err.log" (
  for %%A in ("%LOGS%\oceanengine-service.err.log") do if %%~zA GTR 0 (echo    [!] err.log 有 %%~zA 字节，需查看) else (echo    [OK] err.log 为空)
) else (echo    [OK] err.log 不存在)
echo.
dir /B /O-D "%LOGS%\*.log" 2>nul
echo.
echo    业务日志: %LOGS%\oceanengine-service.out.log   ← 查追投/日报结果看这里
echo    框架日志: %LOGS%\application.log               ← 查配置校验/启动异常看这里
echo    错误日志: %LOGS%\oceanengine-service.err.log
echo.
echo   提示：按任意键关闭本窗口……
pause >nul
endlocal
