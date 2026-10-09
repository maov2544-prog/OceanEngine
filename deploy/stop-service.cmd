@echo off
REM ===================================================================
REM  oceanengine 追投服务 —— 停止服务（需要管理员权限）
REM
REM  注意：本文件必须保存为 ANSI/GBK 编码 + CRLF 换行。
REM        不要改成 UTF-8，也不要加 chcp 65001 —— 会导致 cmd 解析错位。
REM ===================================================================
setlocal
set BASE=D:\oceanengine

net session >nul 2>&1
if errorlevel 1 (
  echo.
  echo  注意：本脚本需要【以管理员身份运行】。
  echo        请右键本文件，选「以管理员身份运行」。
  echo.
  goto :done
)

echo.
echo  正在停止 oceanengine 服务……
echo  最长等待 120 秒，让当前轮次收尾；正常几秒内完成。
echo.
"%BASE%\oceanengine-service.exe" stop
set RC=%errorlevel%
echo.
if not "%RC%"=="0" (
  echo  注意：stop 返回非 0 退出码 %RC%，请以下面的检查结果为准。
) else (
  echo  [OK] 停止命令已执行完成。
)
echo.
echo  ---------- 当前状态 ----------
powershell -NoProfile -Command "$s = Get-Service -Name oceanengine -ErrorAction SilentlyContinue; if ($s) { Write-Host ('    服务: ' + $s.Status) } else { Write-Host '    服务: 未安装' }"
%SystemRoot%\System32\netstat.exe -ano | %SystemRoot%\System32\findstr.exe "127.0.0.1:8080" | %SystemRoot%\System32\findstr.exe "LISTENING" >nul
if errorlevel 1 (echo    端口: 已释放) else (echo    端口: 仍在监听，说明还没完全停下)
echo.
echo  提示：本服务是开机自启，但停服后不会自己再起来。
echo        要重新启动，双击 start-service.cmd。

:done
echo.
echo   提示：按任意键关闭本窗口……
pause >nul
endlocal
