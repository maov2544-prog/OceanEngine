@echo off
REM ===================================================================
REM  oceanengine 追投服务 —— 启动服务（需要管理员权限）
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
echo  正在启动 oceanengine 服务……
"%BASE%\oceanengine-service.exe" start
set RC=%errorlevel%
echo.
if not "%RC%"=="0" (
  echo  注意：start 返回非 0 退出码 %RC%，请以下面的检查结果为准。
) else (
  echo  [OK] 启动命令已执行完成。
)
echo.
echo  等待 10 秒让程序初始化……
%SystemRoot%\System32\timeout.exe /t 10 /nobreak >nul
echo.
echo  ---------- 当前状态 ----------
powershell -NoProfile -Command "$s = Get-Service -Name oceanengine -ErrorAction SilentlyContinue; if ($s) { Write-Host ('    服务: ' + $s.Status) } else { Write-Host '    服务: 未安装' }"
%SystemRoot%\System32\netstat.exe -ano | %SystemRoot%\System32\findstr.exe "127.0.0.1:8080" | %SystemRoot%\System32\findstr.exe "LISTENING"
if errorlevel 1 (echo    端口: 还没监听，稍等几秒再用 status.cmd 复查) else (echo    端口: 已监听)
echo.
echo  提示：完整状态（含健康检查和最近轮次）请双击 status.cmd。

:done
echo.
echo   提示：按任意键关闭本窗口……
pause >nul
endlocal
