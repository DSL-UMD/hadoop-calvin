package org.apache.hadoop.hdfs.nnproxy.server.proxy;

import org.apache.hadoop.hdfs.nnproxy.server.NNProxy;
import org.apache.hadoop.hdfs.nnproxy.server.exception.WrappedExecutionException;
import org.apache.hadoop.hdfs.nnproxy.server.upstream.UpstreamManager;
import org.apache.hadoop.classification.InterfaceAudience;
import org.apache.hadoop.classification.InterfaceStability;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.hdfs.protocol.ClientProtocol;
import org.apache.hadoop.ipc.Server;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.concurrent.ExecutionException;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * This routes path or blockPoolId to backend NameNode corresponding to mount table.
 */
@InterfaceAudience.Private
@InterfaceStability.Stable
public class Router {

    private static final Logger LOG = LoggerFactory.getLogger(Router.class);

    public static final Pattern TRASH_PATTERN = Pattern.compile("/user/[^/]+/.Trash/[^/]+/(.+)");

    final String defaultNN = "hdfs://localhost:9000"; 
    final NNProxy nnProxy;
    final Configuration conf;
    final UpstreamManager upstreamManager;

    public Router(NNProxy nnProxy, Configuration conf, UpstreamManager upstreamManager) {
        this.nnProxy = nnProxy;
        this.conf = conf;
        this.upstreamManager = upstreamManager;
    }

    ClientProtocol getUpstreamProtocol(String user, String fs) throws ExecutionException {
        return upstreamManager.getUpstream(user, fs).protocol;
    }

    RouteInfo route(String path) throws IOException {
        String logicalPath = path;
        Matcher mch = TRASH_PATTERN.matcher(path);
        if (mch.find()) {
            logicalPath = "/" + mch.group(1);
            LOG.debug("Hit trash pattern: " + path + " -> " + logicalPath);
        }
        String fs = nnProxy.getMounts().resolveOpt(logicalPath);
        if (fs == null) {
            // mount to default path
            fs = defaultNN;
        }
        if (LOG.isDebugEnabled()) {
            LOG.debug("Resolved: " + path + " -> " + fs + path);
        }
        return new RouteInfo(getProtocol(fs), path, fs);
    }

    ClientProtocol getProtocol(String fs) throws IOException {
        try {
            return getUpstreamProtocol(Server.getRemoteUser().getUserName(), fs);
        } catch (ExecutionException e) {
            throw new WrappedExecutionException(e.getCause());
        }
    }

    RouteInfo getRoot() throws IOException {
        return route("/");
    }

    ClientProtocol getUpstreamForBlockPool(String bpId) throws IOException {
        String fs = nnProxy.getBlockPoolRegistry().getFs(bpId);
        if (fs == null) {
            throw new IOException("Unknown block pool: " + bpId);
        }
        return getProtocol(fs);
    }

}
