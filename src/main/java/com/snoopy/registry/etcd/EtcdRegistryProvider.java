package com.snoopy.registry.etcd;

import com.snoopy.grpc.base.configure.GrpcRegistryProperties;
import com.snoopy.grpc.base.constans.GrpcConstants;
import com.snoopy.grpc.base.registry.IRegistry;
import com.snoopy.grpc.base.registry.IRegistryProvider;
import io.etcd.jetcd.Client;
import io.etcd.jetcd.ClientBuilder;
import io.grpc.netty.GrpcSslContexts;
import io.netty.handler.ssl.*;
import org.apache.commons.lang3.BooleanUtils;
import org.apache.commons.lang3.StringUtils;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.Arrays;
import java.util.stream.Collectors;

import static java.util.Objects.requireNonNull;

/**
 * @author :   kehanjiang
 * @date :   2021/12/1  15:44
 */
public class EtcdRegistryProvider implements IRegistryProvider {
    public static final String REGISTRY_PROTOCOL_ETCD = "etcd";

    public static final String PARAM_USEPLAINTEXT = "usePlaintext";
    public static final String PARAM_SSLPROVIDER = "SslProvider";
    public static final String PARAM_AUTHORITY = "authority";
    public static final String PARAM_CA_CERTFILE = "caCertFile";
    public static final String PARAM_CERTFILE = "certFile";
    public static final String PARAM_KEYFILE = "keyFile";
    public static final String PARAM_KEY_PASSWORD = "keyPassword";
    public static final String PARAM_ENABLEDOCSP = "enabledOcsp";


    @Override
    public IRegistry newRegistryInstance(GrpcRegistryProperties grpcRegistryProperties) {
        boolean usePlaintext = grpcRegistryProperties.getBooleanExtra(PARAM_USEPLAINTEXT, true);
        String schema = usePlaintext ? "http://" : "https://";
        ClientBuilder builder = Client.builder().endpoints(
                Arrays.stream(GrpcConstants.ADDRESS_SPLIT_PATTERN.split(grpcRegistryProperties.getAddress()))
                        .map(s -> {
                            try {
                                return (new URI(schema + s));
                            } catch (URISyntaxException e) {
                                throw new IllegalArgumentException("Invalid endpoint URI: " + schema + s, e);
                            }
                        }).collect(Collectors.toList()));

        String username = grpcRegistryProperties.getUsername();
        String password = grpcRegistryProperties.getPassword();
        if (!StringUtils.isEmpty(username) && !StringUtils.isEmpty(password)) {
            // 当服务器端开启ssl认证时则该地方的设置就没有意义了.etcd会使用客户端ca证书中的CN头作为用户名进行权限认证
            builder.user(EtcdUtils.byteSequence(username)).password(EtcdUtils.byteSequence(password));
        }
        if (!usePlaintext) {
            String authority = grpcRegistryProperties.getExtra(PARAM_AUTHORITY);
            if (!StringUtils.isEmpty(authority)) {
                builder.authority(authority);
            }
            try {
                builder.sslContext(createSslContext(grpcRegistryProperties));
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        }
        return new EtcdRegistry(grpcRegistryProperties, builder.build());
    }

    protected SslContext createSslContext(GrpcRegistryProperties grpcRegistryProperties) throws Exception {
        SslContextBuilder sslContextBuilder = GrpcSslContexts.forClient();
        String trustCertCollectionFilePath = grpcRegistryProperties.getExtra(PARAM_CA_CERTFILE);
        File trustCertCollection = grpcRegistryProperties.getFileExtra(trustCertCollectionFilePath);
        if (trustCertCollection != null) {
            try (InputStream trustCertCollectionStream = new FileInputStream(trustCertCollection)) {
                sslContextBuilder.trustManager(trustCertCollectionStream);
            } catch (IOException | RuntimeException e) {
                throw new IllegalArgumentException("Failed to create SSLContext (TrustStore)", e);
            }
        }

        // 这里必须要设置alpn,否则会提示ALPN must be enabled and list HTTP/2 as a supported protocol.错误; 这里主要设置了传输协议以及传输过程中的错误解决方式
        ApplicationProtocolConfig alpn = new ApplicationProtocolConfig(ApplicationProtocolConfig.Protocol.ALPN,
                ApplicationProtocolConfig.SelectorFailureBehavior.NO_ADVERTISE,
                ApplicationProtocolConfig.SelectedListenerFailureBehavior.ACCEPT,
                ApplicationProtocolNames.HTTP_2);
        sslContextBuilder.applicationProtocolConfig(alpn);

        String SslProvider = grpcRegistryProperties.getExtra(PARAM_SSLPROVIDER, "OPENSSL");
        sslContextBuilder.sslProvider(Enum.valueOf(SslProvider.class, SslProvider));

        String certFilePath = grpcRegistryProperties.getExtra(PARAM_CERTFILE);
        File certFile = grpcRegistryProperties.getFileExtra(certFilePath);
        requireNonNull(certFile, "client cert file is not configured");
        String keyFilePath = grpcRegistryProperties.getExtra(PARAM_KEYFILE);
        File keyFile = grpcRegistryProperties.getFileExtra(keyFilePath);
        requireNonNull(keyFile, "client key file is not configured");
        String keyPassword = grpcRegistryProperties.getExtra(PARAM_KEY_PASSWORD);
        try (InputStream certFileStream = new FileInputStream(certFile);
             InputStream keyFileStream = new FileInputStream(keyFile)) {
            sslContextBuilder.keyManager(certFileStream, keyFileStream, keyPassword);
        } catch (IOException | RuntimeException e) {
            throw new IllegalArgumentException("Failed to create SSLContext (PK/Cert)", e);
        }
        String enabledOcsp = grpcRegistryProperties.getExtra(PARAM_ENABLEDOCSP);
        if (!StringUtils.isEmpty(enabledOcsp)) {
            sslContextBuilder.enableOcsp(BooleanUtils.toBoolean(enabledOcsp));
        }
        return sslContextBuilder.build();
    }

    @Override
    public String registryType() {
        return REGISTRY_PROTOCOL_ETCD;
    }
}
