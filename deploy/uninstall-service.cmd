@echo off
REM  ---------------------------------------------------------------
REM  本文件必须保存为 ANSI/GBK + CRLF 换行，且不要加 chcp 65001。
REM  原因：cmd.exe 在 65001 代码页下读批处理文件存在缓冲区错位 bug，
REM        会把中文行的后半截当成命令执行（报“不是内部或外部命令”）。
REM  用 VS Code / Notepad++ 编辑时请确认右下角编码是 GB2312/GBK。
REM  ---------------------------------------------------------------
setlocal
title OceanEngine 服务卸载

REM ============================================================
REM  停止并卸载 OceanEngine 随心推追投服务
REM  注意： 必须以【管理员身份】运行
REM
REM  只卸载服务，不删除任何数据文件：
REM    data\reinvest-records.jsonl  幂等流水，删掉会导致重复追投风险
REM    data\reinvest.lock           实例锁
REM    config\application.yml       外部配置
REM ============================================================

set "SERVICE_HOME=D:\oceanengine"
set "WINSW_EXE=%SERVICE_HOME%\oceanengine-service.exe"

if not exist "%WINSW_EXE%" (
    echo   错误：未找到 %WINSW_EXE%
    exit /b 1
)

echo [uninstall] 停止服务，最多等待 120 秒让当前轮次跑完...
"%WINSW_EXE%" stop

echo [uninstall] 卸载服务...
"%WINSW_EXE%" uninstall

echo.
echo [完成] 服务已卸载，数据文件均已保留
echo   幂等流水：%SERVICE_HOME%\data\reinvest-records.jsonl
echo   外部配置：%SERVICE_HOME%\config\application.yml
endlocal
