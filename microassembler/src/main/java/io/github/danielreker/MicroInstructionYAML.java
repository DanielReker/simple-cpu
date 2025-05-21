package io.github.danielreker;

import java.util.List;

public class MicroInstructionYAML {
    public String description;
    public String address; // Hex string like "0x0"
    public String bBus;
    public List<String> writeTo;
    public String operation; // Note: I'm using 'operation' as it's standard, your example had 'operarion'
    public String memoryAction;
    public String next;
    public boolean jZ = false; // Default to false if not specified in YAML

    // No-arg constructor for SnakeYAML
    public MicroInstructionYAML() {}

    // toString for easier debugging if needed (optional)
    @Override
    public String toString() {
        return "MicroInstructionYAML{" +
                "description='" + description + '\'' +
                ", address='" + address + '\'' +
                ", bBus='" + bBus + '\'' +
                ", writeTo=" + writeTo +
                ", operation='" + operation + '\'' +
                ", memoryAction='" + memoryAction + '\'' +
                ", next='" + next + '\'' +
                ", jZ=" + jZ +
                '}';
    }
}
