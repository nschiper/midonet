package com.midokura.mmdpctl;

import com.midokura.mmdpctl.commands.*;
import com.midokura.mmdpctl.commands.results.Result;
import com.midokura.mmdpctl.netlink.NetlinkClient;
import com.midokura.netlink.protos.OvsDatapathConnection;
import org.apache.commons.cli.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * This class is in charge to parse the command line parameters received and invoke the respective Command.
 */
public class Mmdpctl {

    private static final Logger log = LoggerFactory
            .getLogger(Mmdpctl.class);

    private int timeout = 0;

    public void setTimeout(int timeout) {
        this.timeout = timeout;
    }

    public int execute(Command<? extends Result> command) {
        OvsDatapathConnection connection;
        try {
            connection = NetlinkClient.createDatapathConnection();
        } catch (Exception e) {
            System.out.println("Could not connect to netlink: "+ e.getMessage());
            return -1;
        }

        Future<? extends Result> resultFuture = command.execute(connection);

        try {
            Result result = null;
            // if the user supplied a timeout make add it to the Future.get()
            if (timeout > 0) {
                result = resultFuture.get(timeout, TimeUnit.SECONDS);
            } else {
                result = resultFuture.get();
            }

            // display result on screen.
            result.printResult();
        } catch (TimeoutException e) {
            System.out.println("Didn't get result in time. Aborting");
            resultFuture.cancel(true);
            return -1;
        } catch (Exception e) {
            System.out.println("Error while retrieving the datapath: " + e.getMessage());
            return -1;
        }

        return 0;
    }


    public static void main(String ...args) {
        Options options = new Options();

        // The command line tool can only accept one of these options:
        OptionGroup mutuallyExclusiveOptions = new OptionGroup();

        mutuallyExclusiveOptions.addOption(OptionBuilder.withDescription("List all the installed datapaths")
                .isRequired()
                .withLongOpt("list-dps")
                .create());
        mutuallyExclusiveOptions.addOption(OptionBuilder.withDescription("Show all the information related to a given datapath.")
                .hasArg()
                .isRequired()
                .withLongOpt("show-dp")
                .create());
        mutuallyExclusiveOptions.addOption(OptionBuilder.withDescription("Show all the flows installed for a given datapath.")
                .hasArg()
                .isRequired()
                .withLongOpt("dump-dp")
                .create());
        mutuallyExclusiveOptions.addOption(OptionBuilder.withDescription("Add a new datapath.")
                .hasArg()
                .withLongOpt("add-dp")
                .create());
        mutuallyExclusiveOptions.addOption(OptionBuilder.withDescription("Delete a datapath.")
                .hasArg()
                .withLongOpt("delete-dp")
                .create());

        // make sure that there is at least one.
        mutuallyExclusiveOptions.setRequired(true);
        options.addOptionGroup(mutuallyExclusiveOptions);

        // add an optional timeout to the command.
        options.addOption(OptionBuilder.withLongOpt("timeout")
                .hasArg()
                .withDescription("Specifies a timeout in seconds. If the program is " +
                        "not able to get the results in less than this amount of time it will " +
                        "stop and return with an error code")
                .create());

        // TODO burn after using.
        //options.addOption(OptionBuilder.withLongOpt("insert").create());

        CommandLineParser parser = new PosixParser();
        try {
            CommandLine cl = parser.parse(options, args);

            Mmdpctl mmdpctl = new Mmdpctl();

            // check if the user sets a (correct) timeout.
            if (cl.hasOption("timeout")) {
                String timeoutString = cl.getOptionValue("timeout");
                Integer timeout = Integer.parseInt(timeoutString);
                if (timeout > 0) {
                    log.info("Installing a timeout of {} seconds", timeout);
                    mmdpctl.setTimeout(timeout);
                } else {
                    System.out.println("The timeout needs to be a positive number, bigger than 0.");
                    System.exit(-1);
                }
            }

            // WARN ugly dirty horrible hack to populate the flow table.
            // TODO REMOVE THIS.
            if (cl.hasOption("insert")) {
                try {
                new InsertFlows().run();
                } catch (Exception e) {
                    e.printStackTrace();
                }
            }
            ////////////////////////////////


            if (cl.hasOption("list-dps")) {
                System.exit(mmdpctl.execute(new ListDatapathsCommand()));
            } else if (cl.hasOption("show-dp")) {
                System.exit(mmdpctl.execute(new GetDatapathCommand(cl.getOptionValue("show-dp"))));
            } else if (cl.hasOption("dump-dp")) {
                System.exit(mmdpctl.execute(new DumpDatapathCommand(cl.getOptionValue("dump-dp"))));
            } else if (cl.hasOption("add-dp")) {
                System.exit(mmdpctl.execute(new AddDatapathCommand(cl.getOptionValue("add-dp"))));
            } else if (cl.hasOption("delete-dp")) {
                System.exit(mmdpctl.execute(new DeleteDatapathCommand(cl.getOptionValue("delete-dp"))));
            }

        } catch (ParseException e) {
            showHelpAndExit(options, e.getMessage());
        }
    }

    private static void showHelpAndExit(Options options, String message) {
        System.out.println("Error with the options: "+ message);
        HelpFormatter formatter = new HelpFormatter();
        formatter.printHelp( "mm-dpctl", options );
        System.exit(-1);
    }


}