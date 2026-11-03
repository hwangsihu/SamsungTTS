#include <string.h>
#include <sys/system_properties.h>

int property_get(const char *key, char *value, const char *default_value) {
    if (strcmp(key, "ro.product.manufacturer") == 0) {
        strcpy(value, "samsung");
        return (int) strlen(value);
    }
    int len = __system_property_get(key, value);
    if (len > 0) {
        return len;
    }
    len = 0;
    if (default_value != NULL) {
        len = (int) strlen(default_value);
        if (len >= PROP_VALUE_MAX) {
            len = PROP_VALUE_MAX - 1;
        }
        memcpy(value, default_value, (size_t) len);
    }
    value[len] = '\0';
    return len;
}
