!define MUI_VERSION_MAJOR "__VERSION_MAJOR__"
!define MUI_VERSION_MINOR "__VERSION_MINOR__"

!define VER_BUILD __BUILD_NUMBER__

!define MIN_UPGRADE_BUILD __MIN_UPGRADE_BUILD__
!define MAX_UPGRADE_BUILD __MAX_UPGRADE_BUILD__
!define UPGRADE_VERSION __UPGRADE_VERSION__

!define PRODUCT_WITH_VER "${MUI_PRODUCT} ${MUI_VERSION_MAJOR}.${MUI_VERSION_MINOR}"
!define PRODUCT_FULL_NAME_WITH_VER "${PRODUCT_FULL_NAME} ${MUI_VERSION_MAJOR}.${MUI_VERSION_MINOR}"
!define PRODUCT_PATHS_SELECTOR "__PRODUCT_PATHS_SELECTOR__"
!define PRODUCT_SETTINGS_DIR ".${PRODUCT_PATHS_SELECTOR}"