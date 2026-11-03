# Stand-ins for the private platform libraries the 2014 Note 2 engine links against.
LOCAL_PATH := $(call my-dir)

ifeq ($(TARGET_ARCH_ABI),armeabi-v7a)

include $(CLEAR_VARS)
LOCAL_MODULE := cutils
LOCAL_SRC_FILES := cutils.c
include $(BUILD_SHARED_LIBRARY)

include $(CLEAR_VARS)
LOCAL_MODULE := utils
LOCAL_SRC_FILES := utils.c
include $(BUILD_SHARED_LIBRARY)

include $(CLEAR_VARS)
LOCAL_MODULE := expat
LOCAL_SRC_FILES := expat.c
include $(BUILD_SHARED_LIBRARY)

endif
