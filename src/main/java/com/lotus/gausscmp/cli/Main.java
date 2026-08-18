package com.lotus.gausscmp.cli;

import picocli.CommandLine;
import picocli.CommandLine.*;
import java.nio.file.Path;
import java.util.concurrent.Callable;

@Command(name = "gausscmp", mixinStandardHelpOptions = true, version = "gausscmp 0.1.0",
         subcommands = { Main.PicocliCompare.class })
public final class Main implements Callable<Integer> {

    @Override
    public Integer call() {
        System.out.println("用法: gausscmp compare -c <config.yaml>");
        return 0;
    }

    public int execute(String... args) {
        return new CommandLine(this).execute(args);
    }

    public static void main(String[] args) {
        System.exit(new Main().execute(args));
    }

    @Command(name = "compare", description = "比对两个 GaussDB schema")
    public static class PicocliCompare implements Callable<Integer> {
        @Option(names = {"-c", "--config"}, required = true, description = "配置文件路径")
        Path configPath;

        @Override
        public Integer call() {
            try {
                var config = com.lotus.gausscmp.config.ConfigLoader.load(configPath);
                var cmd = new CompareCommand(configPath, config);
                cmd.run();
                return cmd.getExitCode();
            } catch (Exception e) {
                System.err.println("错误: " + e.getMessage());
                return 2;
            }
        }
    }
}
