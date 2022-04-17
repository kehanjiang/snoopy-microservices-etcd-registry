package com.snoopy.registry.etcd;

import com.snoopy.grpc.base.configure.GrpcRegistryProperties;
import com.snoopy.grpc.base.registry.IRegistry;
import com.snoopy.grpc.base.registry.ISubscribeCallback;
import com.snoopy.grpc.base.registry.RegistryServiceInfo;
import com.snoopy.grpc.base.utils.LoggerBaseUtil;
import io.etcd.jetcd.*;
import io.etcd.jetcd.lease.LeaseKeepAliveResponse;
import io.etcd.jetcd.options.GetOption;
import io.etcd.jetcd.options.PutOption;
import io.etcd.jetcd.options.WatchOption;
import io.grpc.stub.StreamObserver;
import org.apache.commons.lang3.math.NumberUtils;


import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.locks.ReentrantLock;
import java.util.stream.Collectors;

/**
 * @author :   kehanjiang
 * @date :   2021/12/1  15:18
 */
public class EtcdRegistry implements IRegistry {
    private final ReentrantLock reentrantLock = new ReentrantLock();
    public static final String PARAM_CONNECT_TIMEOUT = "connectTimeout";
    public static final String PARAM_REGISTRY_SESSIONTIMEOUT = "registrySessionTimeout";
    private static final long DEFAULT_TIMEOUT = 10_000;
    private static final long DEFAULT_LEASE_TTL = 30;


    private long leaseId;
    private long timeout;
    private long leaseTtl;

    private Client etcdClient;
    private KV etcdKvClient;
    private Lease etcdLeaseClient;
    private Watch etcdWatchClient;

    private Map<String, Watch.Watcher> watcherMap = new HashMap<>();

    public EtcdRegistry(GrpcRegistryProperties grpcRegistryProperties, Client client) {
        //获取基础client
        etcdClient = client;
        //获取键值对client
        etcdKvClient = etcdClient.getKVClient();
        //获取租约client
        etcdLeaseClient = etcdClient.getLeaseClient();
        //获取监听client
        etcdWatchClient = etcdClient.getWatchClient();

        leaseTtl = NumberUtils.toLong(grpcRegistryProperties.getExtra(PARAM_REGISTRY_SESSIONTIMEOUT)) / 1000;
        leaseTtl = leaseTtl < 1 ? DEFAULT_LEASE_TTL : leaseTtl;
        timeout = NumberUtils.toLong(grpcRegistryProperties.getExtra(PARAM_CONNECT_TIMEOUT));
        timeout = timeout < 1 ? DEFAULT_TIMEOUT : timeout;

        // 创建一个${leaseTtl} 秒的租约，等待完成，超时设置阈值${timeout} 秒
        leaseId = 0L;
        try {
            leaseId = etcdLeaseClient.grant(this.leaseTtl).get(timeout, TimeUnit.SECONDS).getID();
        } catch (InterruptedException | ExecutionException | TimeoutException e) {
            LoggerBaseUtil.error(this, e.getMessage(), e);
        }

        StreamObserver<LeaseKeepAliveResponse> observer = new StreamObserver<LeaseKeepAliveResponse>() {
            @Override
            public void onNext(LeaseKeepAliveResponse value) {
            }

            @Override
            public void onError(Throwable t) {
                LoggerBaseUtil.error(this, t.getMessage(), t);
            }

            @Override
            public void onCompleted() {
                LoggerBaseUtil.info(this, "complete");
            }
        };
        // 使指定的leaseId租约永久有效，即永久租约
        etcdLeaseClient.keepAlive(leaseId, observer);
    }

    private boolean isReady() {
        return leaseId != 0;
    }

    @Override
    public void subscribe(RegistryServiceInfo serviceInfo, ISubscribeCallback subscribeCallback) {
        reentrantLock.lock();
        try {
            deleteNode(serviceInfo, EtcdKeyType.CLIENT);
            putNode(serviceInfo, EtcdKeyType.CLIENT);
            String serverTypePath = EtcdUtils.toNodeTypePath(serviceInfo, EtcdKeyType.SERVER);
            notifyChange(subscribeCallback, getChildren(serverTypePath));
            Watch.Watcher serviceWatcher = watcherMap.get(serverTypePath);
            if (serviceWatcher == null) {
                serviceWatcher = etcdWatchClient.watch(EtcdUtils.byteSequence(serverTypePath),
                        WatchOption.newBuilder().withPrefix(EtcdUtils.byteSequence(serverTypePath)).build(),
                        (watchResponse) -> {
                            try {
                                notifyChange(subscribeCallback, getChildren(serverTypePath));
                            } catch (Exception e) {
                                throw new RuntimeException(e);
                            }
                        });
                watcherMap.put(serverTypePath, serviceWatcher);
            }
        } catch (Throwable e) {
            throw new RuntimeException("[" + serviceInfo.getPath() + "] subscribe failed !", e);
        } finally {
            reentrantLock.unlock();
        }
    }

    private void notifyChange(ISubscribeCallback subscribeCallback, List<KeyValue> currentChildren) {
        List<RegistryServiceInfo> serviceInfoList = (currentChildren != null && currentChildren.size() > 0) ?
                currentChildren.stream().map(keyValue -> {
                    return new RegistryServiceInfo(EtcdUtils.toStringUtf8(keyValue.getValue()));
                }).collect(Collectors.toList()) : new ArrayList<>();
        subscribeCallback.handle(serviceInfoList);
    }

    @Override
    public void unsubscribe(RegistryServiceInfo serviceInfo) {
        reentrantLock.lock();
        try {
            deleteNode(serviceInfo, EtcdKeyType.CLIENT);
            String serverTypePath = EtcdUtils.toNodeTypePath(serviceInfo, EtcdKeyType.SERVER);
            Watch.Watcher serviceWatcher = watcherMap.get(serverTypePath);
            if (serviceWatcher != null) {
                serviceWatcher.close();
            }
        } catch (Throwable e) {
            throw new RuntimeException("[" + serviceInfo.getPath() + "] unsubscribe failed !", e);
        } finally {
            reentrantLock.unlock();
        }
    }

    @Override
    public void register(RegistryServiceInfo serviceInfo) {
        reentrantLock.lock();
        try {
            if (isReady()) {
                unregister(serviceInfo);
                putNode(serviceInfo, EtcdKeyType.SERVER);
            }
        } catch (Throwable e) {
            throw new RuntimeException("[" + serviceInfo.getPath() + "] register failed !", e);
        } finally {
            reentrantLock.unlock();
        }
    }

    @Override
    public void unregister(RegistryServiceInfo serviceInfo) {
        reentrantLock.lock();
        try {
            if (isReady()) {
                deleteNode(serviceInfo, EtcdKeyType.SERVER);
            }
        } catch (Throwable e) {
            throw new RuntimeException("[" + serviceInfo.getPath() + "] unregister failed !", e);
        } finally {
            reentrantLock.unlock();
        }
    }

    private void putNode(RegistryServiceInfo registryServiceInfo, EtcdKeyType nodeType) throws InterruptedException, ExecutionException, TimeoutException {
        etcdKvClient.put(EtcdUtils.byteSequence(EtcdUtils.toNodePath(registryServiceInfo, nodeType)),
                EtcdUtils.byteSequence(registryServiceInfo.generateData()),
                PutOption.newBuilder().withLeaseId(this.leaseId).build()).get(timeout, TimeUnit.MILLISECONDS);
    }

    private void deleteNode(RegistryServiceInfo registryServiceInfo, EtcdKeyType nodeType) throws InterruptedException, ExecutionException, TimeoutException {
        String nodePath = EtcdUtils.toNodePath(registryServiceInfo, nodeType);
        etcdKvClient.delete(EtcdUtils.byteSequence(nodePath)).get(timeout, TimeUnit.MILLISECONDS);
    }

    private List<KeyValue> getChildren(String parentPath) throws Exception {
        return etcdKvClient.get(EtcdUtils.byteSequence(parentPath),
                GetOption.newBuilder().withPrefix(EtcdUtils.byteSequence(parentPath)).build()).get(timeout,
                TimeUnit.MILLISECONDS).getKvs();
    }

    @Override
    public void close() throws IOException {
        if (etcdKvClient != null) {
            etcdKvClient.close();
        }
        if (etcdLeaseClient != null) {
            etcdLeaseClient.close();
        }
        for (Watch.Watcher watcher : watcherMap.values()) {
            watcher.close();
        }
        if (etcdWatchClient != null) {
            etcdWatchClient.close();
        }
        if (etcdClient != null) {
            etcdClient.close();
        }
    }
}
