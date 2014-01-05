/*
 * Copyright 2011 The Apache Software Foundation
 *
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package my.test.cluster;

import my.test.start.CassandraDaemonStart;

//加vm参数-javaagent:"E:/cassandra/lib/jamm-0.2.5.jar"
//加-Dcom.sun.management.jmxremote可以启用jmx
public class Node1 extends CassandraDaemonStart {
    public static void main(String[] args) {
        setConfigLoader(Node1.class);
        run(args);
    }

    public Node1() {
        this.listen_address = "127.0.0.1";
        this.dir = "cluster/node1";
    }
}
