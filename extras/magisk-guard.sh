#!/system/bin/sh
# 浮译守护脚本：MIUI 偶尔会关闭无障碍服务/悬浮窗权限，每 60 秒检查一次并恢复。
# 若存在「用户主动停止」标记（App 内点「完全停止」会创建），则尊重用户选择不恢复。
PKG=com.sonetto.floattrans
SVC=$PKG/com.sonetto.floattrans.TranslateService
MARK=/data/data/com.sonetto.floattrans/files/disabled

while true; do
  if [ -f "$MARK" ]; then
    sleep 60
    continue
  fi
  cur=$(settings get secure enabled_accessibility_services 2>/dev/null)
  [ "$cur" = "null" ] && cur=""
  case "$cur" in
    *"$SVC"*)
      ;;
    *)
      if [ -z "$cur" ]; then
        settings put secure enabled_accessibility_services "$SVC" 2>/dev/null
      else
        settings put secure enabled_accessibility_services "${cur}:$SVC" 2>/dev/null
      fi
      settings put secure accessibility_enabled 1 2>/dev/null
      echo "$(date) re-enabled accessibility" >> /data/local/tmp/floattrans_guard.log
      ;;
  esac
  op=$(appops get "$PKG" SYSTEM_ALERT_WINDOW 2>/dev/null)
  case "$op" in
    *allow*)
      ;;
    *)
      appops set "$PKG" SYSTEM_ALERT_WINDOW allow 2>/dev/null
      echo "$(date) re-granted overlay" >> /data/local/tmp/floattrans_guard.log
      ;;
  esac
  sleep 60
done
