# Strand engine. Inherit from the device:
#   $(call inherit-product, vendor/strand/strand.mk)
# and install the device's profile lines as
#   /system_ext/etc/strand/device.conf
# (STRAND_DEVICE_CONFIG := path/to/device.conf does that).

PRODUCT_PACKAGES += \
    Strand \
    strand.conf \
    strandctl \
    strandd

ifneq ($(STRAND_DEVICE_CONFIG),)
PRODUCT_COPY_FILES += \
    $(STRAND_DEVICE_CONFIG):$(TARGET_COPY_OUT_SYSTEM_EXT)/etc/strand/device.conf
endif

SYSTEM_EXT_PUBLIC_SEPOLICY_DIRS += vendor/strand/sepolicy/public
SYSTEM_EXT_PRIVATE_SEPOLICY_DIRS += vendor/strand/sepolicy/private

# Strand blur (frameworks/native patch): on by default there, cache on
PRODUCT_SYSTEM_EXT_PROPERTIES += \
    ro.strand.blur.cache=true
