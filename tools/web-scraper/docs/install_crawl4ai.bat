@echo off
set PY=D:\Python\python\python.exe
set LOG=D:\Android\Project\blyy\tools\web-scraper\docs\install.log
set END=D:\Android\Project\blyy\tools\web-scraper\docs\job_end.txt
echo Starting pip install at %DATE% %TIME% > "%LOG%"
"%PY%" -m pip install --prefer-binary -U crawl4ai >> "%LOG%" 2>&1
echo EXIT:%ERRORLEVEL% > "%END%"
echo Finished at %DATE% %TIME% >> "%LOG%"
