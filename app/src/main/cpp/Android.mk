# Stand-ins for the private platform libraries the Note 2 and SM-P600 engines link against, and voicefs,
# which lets the native 2014 engines read voice files without copies.
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

include $(CLEAR_VARS)
LOCAL_MODULE := smtvoicefs
LOCAL_SRC_FILES := voicefs.c
LOCAL_LDLIBS := -ldl
include $(BUILD_SHARED_LIBRARY)

endif
