#!/system/bin/sh
cp /data/data/com.termux/files/home/apkbuild/PiWebLauncher.apk /data/local/tmp/pw.apk
chmod 644 /data/local/tmp/pw.apk
pm install -r -g /data/local/tmp/pw.apk
pm grant com.piweb.launcher com.termux.permission.RUN_COMMAND
echo "installed:"
cmd package dump com.piweb.launcher 2>/dev/null | grep -E "versionName" | head -1
