# https://www.gnu.org/software/make/manual/make.html

LOCAL_PATH := $(call my-dir)

PB_ASSEMBLE_TYPE := assembleRelease
PB_LOCAL_PATH_ := $(LOCAL_PATH)
PB_PACKAGE_NAME := PowerBalancing
PB_APK_NAME := $(PB_PACKAGE_NAME).apk
ifeq ($(PB_ASSEMBLE_TYPE),assembleRelease)
PB_APK_PATH_REL := app/build/outputs/apk/release/$(PB_APK_NAME)
PB_OUT_PATH_REL := app/build/outputs/apk/release/app-release-unsigned.apk
else
PB_APK_PATH_REL := app/build/outputs/apk/debug/$(PB_APK_NAME)
PB_OUT_PATH_REL := app/build/outputs/apk/debug/app-debug.apk
endif
PB_APK_PATH := $(LOCAL_PATH)/$(PB_APK_PATH_REL)
PB_APK_SO_DIR := lib/armeabi-v7a/*
TEMP_DIR := temp/
TEMP_PATH := $(LOCAL_PATH)/$(TEMP_DIR)
TEMP_SO_DIR := $(TEMP_DIR)lib/armeabi-v7a/
TEMP_SO_PATH := $(LOCAL_PATH)/$(TEMP_SO_DIR)

PB_JAVA_GRADLE := /usr/lib/jvm/java-8-openjdk-amd64
ifeq ($(PRODUCT_ANDROID_VERSION),8.1)
  SO_LIBS_DST_PATH := $(TARGET_OUT)/priv-app/PowerBalancing/lib/arm
  PB_JAVA_MAIN := /usr/lib/jvm/java-8-openjdk-amd64
else ifeq ($(PRODUCT_ANDROID_VERSION),7)
  SO_LIBS_DST_PATH := $(TARGET_OUT)/priv-app/PowerBalancing/lib/arm
  PB_JAVA_MAIN := /usr/lib/jvm/java-7-oracle
else
  SO_LIBS_DST_PATH := $(TARGET_OUT)/app/PowerBalancing/lib/arm
  PB_JAVA_MAIN := /usr/lib/jvm/java-7-oracle
endif


# Build with gradle
$(PB_APK_PATH):
	@echo "Building PowerBalancing with Gradle ..."
	export ANDROID_HOME=$(ANDROID_BUILD_TOP)/android_gromaudio/packages/apps/DashLinQ/android_sdk; \
	export JAVA_HOME=$(PB_JAVA_GRADLE); \
	export ANDROID_BUILD_TOP=$(ANDROID_BUILD_TOP); \
	$(PB_LOCAL_PATH_)/gradlew $(PB_ASSEMBLE_TYPE) -p $(PB_LOCAL_PATH_)/;
	@echo "Rename $(PB_OUT_PATH_REL) to $(PB_APK_NAME)"
	mv $(PB_LOCAL_PATH_)/$(PB_OUT_PATH_REL)  $(PB_APK_PATH)
	

#Need to prebuild PowerBalancing (first time)
ifeq ($(wildcard $(PB_APK_PATH)),)
  #1. Build PowerBalancing with Gradle system to $(LOCAL_PATH)/app/apk/
  $(info Prebuild PowerBalancing with Gradle (first time) ...)
  $(info $(shell (export ANDROID_HOME=$(ANDROID_BUILD_TOP)/android_gromaudio/packages/apps/DashLinQ/android_sdk; \
	        export JAVA_HOME=$(PB_JAVA_GRADLE); \
		export ANDROID_BUILD_TOP=$(ANDROID_BUILD_TOP); \
	        $(LOCAL_PATH)/gradlew $(PB_ASSEMBLE_TYPE) -p $(PB_LOCAL_PATH_)/; \
	        export JAVA_HOME=$(PB_JAVA_MAIN)) ) )

  #2. Move $(LOCAL_PATH)/app/build/outputs/apk/release/app-release.apk to $(LOCAL_PATH)/app/build/outputs/apk/release/PowerBalancing.apk
  $(info Rename $(PB_OUT_PATH_REL) to $(PB_APK_NAME))
  $(info $(shell (mv $(LOCAL_PATH)/$(PB_OUT_PATH_REL)  $(PB_APK_PATH) )) )
endif

# Add rule
all_modules: $(PB_APK_PATH)
.PHONY: $(PB_APK_PATH)

include $(CLEAR_VARS)
LOCAL_MODULE_TAGS := optional
LOCAL_MODULE := $(PB_PACKAGE_NAME)
##LOCAL_CERTIFICATE := PRESIGNED
LOCAL_CERTIFICATE := platform
#LOCAL_REQUIRED_MODULES := $(SO_LIBS)
LOCAL_SRC_FILES := $(PB_APK_PATH_REL)
LOCAL_MODULE_CLASS := APPS
LOCAL_MODULE_SUFFIX := $(COMMON_ANDROID_PACKAGE_SUFFIX)

#//VLine-- 8.1
ifeq ($(PRODUCT_ANDROID_VERSION),8.1)
  LOCAL_PRIVILEGED_MODULE := false
else ifeq ($(PRODUCT_ANDROID_VERSION),7)
  LOCAL_PRIVILEGED_MODULE := false
endif

#LOCAL_DEX_PREOPT := false

include $(BUILD_PREBUILT)

