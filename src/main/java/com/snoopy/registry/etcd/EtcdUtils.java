package com.snoopy.registry.etcd;

import com.google.protobuf.ByteString;
import com.snoopy.grpc.base.constans.GrpcConstants;
import com.snoopy.grpc.base.registry.RegistryServiceInfo;
import io.etcd.jetcd.ByteSequence;

import java.nio.charset.Charset;

/**
 * @author :   kehanjiang
 * @date :   2022/4/8  15:52
 */
public class EtcdUtils {
    public static ByteSequence byteSequence(String str) {
        return str != null ? ByteSequence.from(bs(str)) : null;
    }

    public static ByteString bs(String str) {
        return str != null ? ByteString.copyFromUtf8(str) : null;
    }


    public static String toNodeTypePath(RegistryServiceInfo registryServiceInfo, EtcdKeyType nodeType) {
        return registryServiceInfo.getPath() + GrpcConstants.PATH_SEPARATOR + nodeType.getValue();
    }

    public static String toNodePath(RegistryServiceInfo registryServiceInfo, EtcdKeyType nodeType) {
        return toNodeTypePath(registryServiceInfo, nodeType) + GrpcConstants.PATH_SEPARATOR + registryServiceInfo.getHostAndPort();
    }

    public static String toStringUtf8(ByteSequence value) {
        if (value == null)
            return null;
        return value.toString(Charset.forName("UTF-8"));
    }
}
