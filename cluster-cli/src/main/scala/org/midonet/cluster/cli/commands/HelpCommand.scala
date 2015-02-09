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

package org.midonet.cluster.cli.commands

import org.midonet.cluster.cli.commands.Command.Run

/**
 * Implements the HELP command.
 */
class HelpCommand extends Command {
    override val name = "help"
    override def run: Run = super.run orElse {
        case args if 0 == args.length =>
            println(help)
            CommandSuccess
    }
}