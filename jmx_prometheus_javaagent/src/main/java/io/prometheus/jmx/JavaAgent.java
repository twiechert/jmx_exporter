package io.prometheus.jmx;

import java.io.File;
import java.lang.instrument.Instrumentation;
import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import io.prometheus.client.CollectorRegistry;
import io.prometheus.client.exporter.HTTPServer;
import io.prometheus.client.hotspot.DefaultExports;

public class JavaAgent {

    static HTTPServer server;

    public static void agentmain(String agentArguments, Instrumentation instrumentation) throws Exception {
        premain(agentArguments, instrumentation);
    }

    public static void premain(String agentArguments, Instrumentation instrumentation) throws Exception {
        // Bind to all interfaces by default (this includes IPv6).
        String host = "0.0.0.0";

        try {
            Config[] configs = parseConfig(agentArguments, host);

            for (Config config : configs) {
                new BuildInfoCollector().register();
                new JmxCollector(new File(config.file)).register();
                DefaultExports.initialize();
                server = new HTTPServer(config.socket, CollectorRegistry.defaultRegistry, true);
            }

        } catch (IllegalArgumentException e) {
            System.err.println("Usage: -javaagent:/path/to/JavaAgent.jar=[host:]<port>:<yaml configuration file>");
            System.exit(1);
        }
    }

    /**
     * Parse the Java Agent configuration. The arguments are typically specified to the JVM as a javaagent as
     * {@code -javaagent:/path/to/agent.jar=<CONFIG>}. This method parses the {@code <CONFIG>} portion.
     *
     * @param args provided agent args
     * @param ifc  default bind interface
     * @return configuration to use for our application
     */
    public static Config[] parseConfig(String args, String ifc) {
        Pattern pattern = Pattern.compile(
                "(?:((?:[\\w.]+)|(?:\\[.+])):)?" +  // host name, or ipv4, or ipv6 address in brackets
                        "(\\d{1,5}):" +              // port
                        "([^\\|]+)");                     // config file

        List<Config> configList = new ArrayList<Config>();
        Matcher matcher = pattern.matcher(args);

        boolean matched = false;

        while (matcher.find()) {

            String givenHost = matcher.group(1);
            String givenPort = matcher.group(2);
            String givenConfigFile = matcher.group(3);


            int port = Integer.parseInt(givenPort);

            InetSocketAddress socket;
            if (givenHost != null && !givenHost.isEmpty()) {
                socket = new InetSocketAddress(givenHost, port);
            } else {
                socket = new InetSocketAddress(ifc, port);
                givenHost = ifc;
            }

            configList.add(new Config(givenHost, port, givenConfigFile, socket));
            matched = true;
        }

        if (!matched) {
            throw new IllegalArgumentException("Malformed arguments - " + args);
        }

        if(new HashSet<>(configList.stream().map(c -> c.host).collect(Collectors.toList())).size() < configList.size()) {
            throw new IllegalArgumentException("Must not specify two exporters with same ifc/port pair - " + args);
        }

        return configList.toArray(new Config[configList.size()]);
    }

    static class Config {
        String host;
        int port;
        String file;
        InetSocketAddress socket;

        Config(String host, int port, String file, InetSocketAddress socket) {
            this.host = host;
            this.port = port;
            this.file = file;
            this.socket = socket;
        }
    }
}
