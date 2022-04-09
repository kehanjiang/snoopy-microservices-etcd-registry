package com.snoopy.registry.etcd;

/**
 * @author :   kehanjiang
 * @date :   2021/12/1  15:18
 */
public enum EtcdKeyType {

    SERVER("server"),
    CLIENT("client"),
    ;
    private String value;

    public String getValue() {
        return value;
    }

    public void setValue(String value) {
        this.value = value;
    }

    EtcdKeyType(String value) {
        this.value = value;
    }
}
