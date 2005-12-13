/**
 * <a href="http://activemq.org">ActiveMQ: The Open Source Message Fabric</a>
 *
 * Copyright 2005 (C) LogicBlaze, Inc. http://www.logicblaze.com
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 **/
package org.activemq.transport.vm;

import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.HashMap;
import java.util.Map;

import org.activemq.broker.BrokerFactory;
import org.activemq.broker.BrokerRegistry;
import org.activemq.broker.BrokerService;
import org.activemq.broker.TransportConnector;
import org.activemq.broker.BrokerFactory.BrokerFactoryHandler;
import org.activemq.transport.MarshallingTransportFilter;
import org.activemq.transport.Transport;
import org.activemq.transport.TransportFactory;
import org.activemq.transport.TransportServer;
import org.activemq.util.IOExceptionSupport;
import org.activemq.util.IntrospectionSupport;
import org.activemq.util.ServiceSupport;
import org.activemq.util.URISupport;
import org.activemq.util.URISupport.CompositeData;

import edu.emory.mathcs.backport.java.util.concurrent.ConcurrentHashMap;

public class VMTransportFactory extends TransportFactory {

    final public static ConcurrentHashMap brokers = new ConcurrentHashMap();
    final public static ConcurrentHashMap connectors = new ConcurrentHashMap();
    final public static ConcurrentHashMap servers = new ConcurrentHashMap();

    BrokerFactoryHandler brokerFactoryHandler;
    
    public Transport doConnect(URI location) throws Exception {
        return VMTransportServer.configure(doCompositeConnect(location));
    }

    public Transport doCompositeConnect(URI location) throws Exception {
        URI brokerURI;
        String host;
        Map options;

        CompositeData data = URISupport.parseComposite(location);
        if( data.getComponents().length==1 && "broker".equals(data.getComponents()[0].getScheme()) ) {
            brokerURI = data.getComponents()[0];
            
            CompositeData brokerData = URISupport.parseComposite(brokerURI);
            host = (String)brokerData.getParameters().get("brokerName");
            if( host == null )
                host = "localhost";
            if( brokerData.getPath()!=null )
                host = data.getPath();
            
            options = data.getParameters();
            location = new URI("vm://"+host);
        } else {
            // If using the less complex vm://localhost?broker.persistent=true form
            try {
                host =  location.getHost();
                options = URISupport.parseParamters(location);
                String config = (String) options.remove("brokerConfig");
                if( config != null ) {
                    brokerURI = new URI(config);
                } else {
                    Map brokerOptions = IntrospectionSupport.extractProperties(options, "broker.");
                    brokerURI = new URI("broker://()/"+host+"?"+URISupport.createQueryString(brokerOptions));
                }
            } catch (URISyntaxException e1) {
                throw IOExceptionSupport.create(e1);
            }
            
            location = new URI("vm://"+host);
        }
        
        VMTransportServer server = (VMTransportServer) servers.get(host);        
        if( server == null ) {
            BrokerService broker = BrokerRegistry.getInstance().lookup(host);
            if (broker == null) {
                try {
                    if( brokerFactoryHandler !=null ) {
                        broker = brokerFactoryHandler.createBroker(brokerURI);
                    } else {
                        broker = BrokerFactory.createBroker(brokerURI);
                    }
                    broker.start();
                }
                catch (URISyntaxException e) {
                    throw IOExceptionSupport.create(e);
                }
                brokers.put(host, broker);
            }
            server = (VMTransportServer) servers.get(host);
            if (server == null) {
                server = (VMTransportServer) bind(location, true);
                TransportConnector connector = new TransportConnector(broker.getBroker(), server);
                connector.start();
                connectors.put(host, connector);
            }
        }

        VMTransport vmtransport = server.connect();
        IntrospectionSupport.setProperties(vmtransport, options);

        Transport transport = vmtransport;
        if (vmtransport.isMarshal()) {
            HashMap optionsCopy = new HashMap(options);
            transport = new MarshallingTransportFilter(transport, createWireFormat(options), createWireFormat(optionsCopy));
        }

        if( !options.isEmpty() ) {
            throw new IllegalArgumentException("Invalid connect parameters: "+options);
        }
        
        return transport;
    }

    public TransportServer doBind(String brokerId,URI location) throws IOException {
        return bind(location, false);
    }

    /**
     * @param location
     * @return
     * @throws IOException
     */
    private TransportServer bind(URI location, boolean dispose) throws IOException {
        String host = location.getHost();
        VMTransportServer server = new VMTransportServer(location, dispose);
        Object currentBoundValue = servers.get(host);
        if (currentBoundValue != null) {
            throw new IOException("VMTransportServer already bound at: " + location);
        }
        servers.put(host, server);
        return server;
    }

    public static void stopped(VMTransportServer server) {
        String host = server.getBindURI().getHost();
        servers.remove(host);
        TransportConnector connector = (TransportConnector) connectors.remove(host);
        if (connector != null) {
            ServiceSupport.dispose(connector);
            BrokerService broker = (BrokerService) brokers.remove(host);
            if (broker != null) {
                ServiceSupport.dispose(broker);
            }
        }
    }

    public BrokerFactoryHandler getBrokerFactoryHandler() {
        return brokerFactoryHandler;
    }

    public void setBrokerFactoryHandler(BrokerFactoryHandler brokerFactoryHandler) {
        this.brokerFactoryHandler = brokerFactoryHandler;
    }

}
