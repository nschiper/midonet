/*
 * Copyright 2014 Midokura SARL
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
package org.midonet.mmdpctl.commands.results;

import org.midonet.odp.Datapath;
import org.midonet.odp.DpPort;

import java.io.OutputStream;
import java.io.PrintStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Set;

public class GetDatapathResult implements Result {

    Datapath datapath;
    Set<DpPort> ports;

    public GetDatapathResult(Datapath datapath, Set<DpPort> ports) {
        this.datapath = datapath;
        this.ports = ports;
    }

    public String assembleString(DpPort p) {
        return "Port #" + p.getPortNo() + " \"" + p.getName() + "\"  "
            + p.getType().toString() +  "  " + p.getStats().toString();
    }

    public ArrayList<DpPort> sortPorts() {
        ArrayList<DpPort> toPrint = new ArrayList<>(ports);

        Collections.sort(toPrint, new Comparator<DpPort>() {
            @Override public int compare(DpPort o1, DpPort o2) {
                return o1.getPortNo().compareTo(o2.getPortNo());
            }
        });

        return toPrint;
    }

    @Override
    public void printResult(OutputStream stream) {
        PrintStream out = new PrintStream(stream);
        out.println("Datapath name   : " + datapath.getName());
        out.println("Datapath index : " + datapath.getIndex());
        Datapath.Stats stats = datapath.getStats();
        out.println("Datapath Stats: ");
        out.println("  Flows :"+stats.getFlows());
        out.println("  Hits  :"+stats.getHits());
        out.println("  Lost  :"+stats.getLost());
        out.println("  Misses:" +stats.getMisses());
        if (ports != null && (!ports.isEmpty())) {
            for (DpPort port: sortPorts()) {
                out.println(assembleString(port));
            }
        } else {
            out.println("Datapath does not contain any port.");
        }
    }

    @Override
    public void printResult() {
        printResult(System.out);
    }

}
