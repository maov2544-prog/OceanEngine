@echo off
REM  ---------------------------------------------------------------
REM  本文件必须保存为 ANSI/GBK + CRLF 换行，且不要加 chcp 65001。
REM  原因：cmd.exe 在 65001 代码页下读批处理文件存在缓冲区错位 bug，
REM        会把中文行的后半截当成命令执行（报“不是内部或外部命令”）。
REM  用 VS Code / Notepad++ 编辑时请确认右下角编码是 GB2312/GBK。
REM  ---------------------------------------------------------------
setlocal
title OceanEngine 服务重启

REM ============================================================
REM  重启 OceanEngine 随心推追投服务
REM  改完 config\application.yml 里的业务参数后，双击本脚本即可生效
REM  注意： 需要管理员权限
REM ============================================================

set "SERVICE_HOME=D:\oceanengine"
set "WINSW_EXE=%SERVICE_HOME%\oceanengine-service.exe"

if not exist "%WINSW_EXE%" (
    echo   错误：未找到 %WINSW_EXE%
    exit /b 1
)

echo [restart] 停止服务，最多等待 120 秒让当前轮次跑完...
"%WINSW_EXE%" stop

echo [restart] 启动服务...
"%WINSW_EXE%" start

if errorlevel 1 (
    echo   错误：服务启动失败
    echo   查看 %SERVICE_HOME%\logs\oceanengine-service.err.log
    exit /b 1
)

echo.
echo [完成] 服务已重启
echo   状态查询：sc query oceanengine
endlocal
