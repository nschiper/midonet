/*
 * Copyright 2015 Midokura SARL
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.midonet.midolman.config;

import org.midonet.config.ConfigBool;
import org.midonet.config.ConfigGroup;
import org.midonet.config.ConfigString;

/**
 * Interface that provides access to various configuration values related to
 * the agent communication channel with cluster
 */
@ConfigGroup(ClusterConfig.GROUP_NAME)
public interface ClusterConfig {

    public static final String GROUP_NAME = "cluster";

    /**
     * Get the connection string to the database containing the tasks table
     *
     * @return mysql connection string
     */
    @ConfigString(key = "tasks_db_connection",
        defaultValue = "mysql://localhost/neutron?user=root&password=midonet")
    public String getTasksDbConn();

    /**
     * Get the enable value signifying whether or not the cluster deployment
     * should be running.
     */
    @ConfigBool(key = "enabled", defaultValue = false)
    public boolean getClusterEnabled();

}
