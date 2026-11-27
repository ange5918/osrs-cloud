@echo off
chcp 65001 >nul
cd /d "%~dp0"
echo ========================================
echo   myplugin 一键打包上传
echo   编译 dex -^> 更新 manifest -^> git push gh-pages
echo ========================================
echo.
python deploy.py -m "myplugin: one-click deploy"
echo.
echo ========================================
if errorlevel 1 (
  echo   失败! 请检查上面的错误信息
) else (
  echo   完成! https://ange5918.github.io/osrs-cloud/
)
echo ========================================
pause
