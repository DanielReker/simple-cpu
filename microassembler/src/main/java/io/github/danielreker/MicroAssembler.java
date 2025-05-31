package io.github.danielreker;

import org.yaml.snakeyaml.Yaml;

import java.io.*;
import java.util.*;

public class MicroAssembler {

    // Bit positions for writeTo registers (bits 0-8)
    private static final Map<String, Integer> WRITE_ENABLE_BITS = new LinkedHashMap<>();
    static {
        WRITE_ENABLE_BITS.put("MAR", 0);
        WRITE_ENABLE_BITS.put("MBR", 1);
        WRITE_ENABLE_BITS.put("PC", 2);
        WRITE_ENABLE_BITS.put("SP", 3);
        WRITE_ENABLE_BITS.put("B", 4); // Assuming 'B' here refers to your B register, not bBus
        WRITE_ENABLE_BITS.put("A", 5); // Assuming 'A' here refers to your A register
        WRITE_ENABLE_BITS.put("BUF", 6); // Assuming 'Buf' from your description
        WRITE_ENABLE_BITS.put("OUT", 7);
    }

    // B bus encodings (bits 9-11)
    private static final Map<String, Integer> B_BUS_SOURCES = new HashMap<>();
    static {
        B_BUS_SOURCES.put("A", 0b000);
        B_BUS_SOURCES.put("B", 0b001);
        B_BUS_SOURCES.put("SP", 0b010);
        B_BUS_SOURCES.put("PC", 0b011);
        B_BUS_SOURCES.put("MBR", 0b100);
        B_BUS_SOURCES.put("MAR", 0b101);
        B_BUS_SOURCES.put("INPUT", 0b110);
        // 111 is reserved
    }

    // ALU operation encodings (bits 12-15)
    private static final Map<String, Integer> ALU_OPERATIONS = new HashMap<>();
    static {
        ALU_OPERATIONS.put("A", 0b0000);
        ALU_OPERATIONS.put("B", 0b0001);
        ALU_OPERATIONS.put("APLUS1", 0b0010);
        ALU_OPERATIONS.put("BPLUS1", 0b0011);
        ALU_OPERATIONS.put("APLUSB", 0b0100);
        ALU_OPERATIONS.put("AMINUSB",0b0101);
        ALU_OPERATIONS.put("AANDB",  0b0110);
        ALU_OPERATIONS.put("AORB",   0b0111);
        ALU_OPERATIONS.put("BMINUS1",   0b1000);
        // 1001-1111 reserved
    }

    private static final int MAX_ADDRESS = 0x3F; // 6-bit address space (0-63)

    public static void main(String[] args) {
        String inputFile = "microcode.yaml";
        String outputFile = "microcode.bin";
        String outputLogisimImageFile = "microcode.logisimimg";


        MicroAssembler assembler = new MicroAssembler();
        try {
            List<MicroInstructionYAML> yamlInstructions = assembler.loadYAML(inputFile);
            Map<Integer, Integer> assembledCode = new TreeMap<>(); // TreeMap to keep addresses sorted
            Map<Integer, String> descriptions = new TreeMap<>();

            System.out.println("Assembling microcode...\n");

            for (MicroInstructionYAML yamlInstr : yamlInstructions) {
                int address = Integer.parseInt(yamlInstr.address.substring(2), 16);
                if (address < 0 || address > MAX_ADDRESS) {
                    System.err.println("Error: Address " + yamlInstr.address + " is out of range for instruction: " + yamlInstr.description);
                    return;
                }
                if (assembledCode.containsKey(address)) {
                    System.err.println("Error: Duplicate address " + yamlInstr.address + " for instruction: " + yamlInstr.description);
                    return;
                }

                int binaryInstruction = assembler.assembleInstruction(yamlInstr);
                assembledCode.put(address, binaryInstruction);
                descriptions.put(address, yamlInstr.description != null ? yamlInstr.description : "N/A");
            }

            assembler.printToConsole(assembledCode, descriptions);
            assembler.writeBinary(outputFile, assembledCode);
            assembler.writeLogisimImage(outputLogisimImageFile, assembledCode);

            System.out.println("\nAssembly complete. Output written to " + outputFile);

        } catch (FileNotFoundException e) {
            System.err.println("Error: YAML input file not found: " + inputFile);
        } catch (Exception e) {
            System.err.println("An error occurred during assembly:");
            e.printStackTrace();
        }
    }

    @SuppressWarnings("unchecked") // For SnakeYAML's raw list of maps
    public List<MicroInstructionYAML> loadYAML(String filePath) throws FileNotFoundException {
        Yaml yaml = new Yaml();
        InputStream inputStream = new FileInputStream(filePath);
        List<Map<String, Object>> rawList = yaml.load(inputStream);
        List<MicroInstructionYAML> instructions = new ArrayList<>();

        for (Map<String, Object> rawInstr : rawList) {
            MicroInstructionYAML instr = new MicroInstructionYAML();
            instr.description = (String) rawInstr.get("description");
            instr.address = (String) rawInstr.get("address");
            instr.bBus = (String) rawInstr.get("bBus");
            instr.writeTo = (List<String>) rawInstr.get("writeTo");
            instr.operation = (String) rawInstr.get("operation");
            instr.memoryAction = (String) rawInstr.get("memoryAction");
            instr.next = (String) rawInstr.get("next");
            instr.jZ = rawInstr.containsKey("jZ") ? (Boolean) rawInstr.get("jZ") : false;
            instructions.add(instr);
        }
        return instructions;
    }


    public int assembleInstruction(MicroInstructionYAML yamlInstr) {
        int microcodeWord = 0;

        // Bits 0-7: writeTo
        boolean writeToMbr = false;
        if (yamlInstr.writeTo != null) {
            for (String reg : yamlInstr.writeTo) {
                if ("MBR".equalsIgnoreCase(reg)) {
                    writeToMbr = true;
                    continue;
                }

                Integer bitPos = WRITE_ENABLE_BITS.get(reg.toUpperCase());
                if (bitPos != null) {
                    microcodeWord |= (1 << bitPos);
                } else {
                    System.err.println("Warning: Unknown register in writeTo: " + reg + " for instruction at " + yamlInstr.address);
                }
            }
        }

        // Bit 8: memoryAction
        if (!writeToMbr && "none".equalsIgnoreCase(yamlInstr.memoryAction)) {
            // Do nothing
        } else if (!writeToMbr && "read".equalsIgnoreCase(yamlInstr.memoryAction)) {
            microcodeWord |= (1 << WRITE_ENABLE_BITS.get("MBR"));
        } else if (!writeToMbr && "write".equalsIgnoreCase(yamlInstr.memoryAction)) {
            microcodeWord |= (1 << 8);
        } else if (writeToMbr && "none".equalsIgnoreCase(yamlInstr.memoryAction)) {
            microcodeWord |= (1 << 8) | (1 << WRITE_ENABLE_BITS.get("MBR"));
        } else {
            System.err.println("Warning: Incorrect combination on write: [...mbr?...] and memoryAction");
        }


        // Bits 9-11: bBus
        if (yamlInstr.bBus != null) {
            Integer bBusVal = B_BUS_SOURCES.get(yamlInstr.bBus.toUpperCase());
            if (bBusVal != null) {
                microcodeWord |= (bBusVal << 9);
            } else {
                System.err.println("Warning: Unknown bBus source: " + yamlInstr.bBus + " for instruction at " + yamlInstr.address);
            }
        }

        // Bits 12-15: operation
        if (yamlInstr.operation != null) {
            Integer aluOpVal = ALU_OPERATIONS.get(yamlInstr.operation.toUpperCase());
            if (aluOpVal != null) {
                microcodeWord |= (aluOpVal << 12);
            } else {
                System.err.println("Warning: Unknown ALU operation: " + yamlInstr.operation + " for instruction at " + yamlInstr.address);
            }
        }

        // Bit 16: J bit
        int nextAddrVal = 0;
        if (yamlInstr.next != null) {
            if ("mbr".equalsIgnoreCase(yamlInstr.next)) {
                // J bit is 0 (default)
            } else {
                microcodeWord |= (1 << 16); // Set J bit
                try {
                    nextAddrVal = Integer.parseInt(yamlInstr.next.substring(2), 16);
                    if (nextAddrVal < 0 || nextAddrVal > MAX_ADDRESS) {
                        System.err.println("Warning: Next address " + yamlInstr.next + " out of range for instruction at " + yamlInstr.address);
                        nextAddrVal &= MAX_ADDRESS; // Mask to 6 bits
                    }
                } catch (NumberFormatException e) {
                    System.err.println("Warning: Invalid next address format: " + yamlInstr.next + " for instruction at " + yamlInstr.address);
                }
            }
        }

        // Bit 17: JZ bit
        if (yamlInstr.jZ) {
            microcodeWord |= (1 << 17);
        }

        // Bits 18-23: Address of next microinstruction (if J bit is 1)
        if ((microcodeWord & (1 << 16)) != 0) { // if J bit is set
            microcodeWord |= (nextAddrVal << 18);
        }
        // If J bit is 0, these bits effectively don't care or could be zeroed for consistency,
        // but hardware should ignore them based on J bit.

        return microcodeWord;
    }

    public void printToConsole(Map<Integer, Integer> assembledCode, Map<Integer, String> descriptions) {
        System.out.println("Address | Hex Value | Binary Value                     | Description");
        System.out.println("--------|-----------|----------------------------------|-------------");
        for (Map.Entry<Integer, Integer> entry : assembledCode.entrySet()) {
            int address = entry.getKey();
            int instruction = entry.getValue();
            String binaryString = String.format("%24s", Integer.toBinaryString(instruction)).replace(' ', '0');
            // Insert spaces for readability
            StringBuilder formattedBinary = new StringBuilder();
            for(int i=0; i < binaryString.length(); i++) {
                if (i > 0 && i % 4 == 0) {
                    formattedBinary.append(' ');
                }
                formattedBinary.append(binaryString.charAt(i));
            }

            System.out.printf("0x%02X    | 0x%06X  | %-32s | %s%n",
                    address,
                    instruction,
                    formattedBinary.toString(),
                    descriptions.get(address));
        }
    }

    public void writeBinary(String filePath, Map<Integer, Integer> assembledCode) throws IOException {
        // We need to write a continuous block of ROM, filling unspecified addresses with 0 or a default NOP.
        // For now, we'll just write the specified ones.
        // To create a full ROM image, you'd iterate from 0 to MAX_ADDRESS.
        byte[] romImage = new byte[(MAX_ADDRESS + 1) * 3]; // 3 bytes per 24-bit instruction

        for (Map.Entry<Integer, Integer> entry : assembledCode.entrySet()) {
            int address = entry.getKey();
            int instruction = entry.getValue();
            int offset = address * 3;

            romImage[offset]     = (byte) ((instruction >> 16) & 0xFF); // MSB
            romImage[offset + 1] = (byte) ((instruction >> 8) & 0xFF);
            romImage[offset + 2] = (byte) (instruction & 0xFF);        // LSB
        }

        try (FileOutputStream fos = new FileOutputStream(filePath)) {
            fos.write(romImage);
        }
    }

    public void writeLogisimImage(String filePath, Map<Integer, Integer> assembledCode) throws IOException {
        List<String> hexWords = new ArrayList<>();
        int lastNonZeroInstructionAddress = -1;

        // Prepare all words, find the last non-zero one
        for (int i = 0; i <= MAX_ADDRESS; i++) {
            int instruction = assembledCode.getOrDefault(i, 0);
            // Convert to hex, Integer.toHexString() naturally strips leading zeros except for "0"
            String hexWord = Integer.toHexString(instruction);
            hexWords.add(hexWord);
            if (instruction != 0) {
                lastNonZeroInstructionAddress = i;
            }
        }

        try (PrintWriter writer = new PrintWriter(new FileWriter(filePath))) {
            writer.print("v2.0 raw\n"); // Logisim header

            if (lastNonZeroInstructionAddress == -1) {
                // If all instructions are zero (or no instructions defined),
                // Logisim expects a "0" if the ROM is meant to be non-empty but all zeros.
                // If the YAML was completely empty, an empty data line might be okay,
                // but "0" is safer for an initialized ROM.
                if (MAX_ADDRESS >= 0) { // Ensure there's at least one potential address
                    writer.print("0");
                }
            } else {
                for (int i = 0; i <= lastNonZeroInstructionAddress; i++) {
                    writer.print(hexWords.get(i));
                    if (i < lastNonZeroInstructionAddress) {
                        writer.print(" ");
                    }
                }
            }
            writer.println(); // Final newline for the data part
        }
    }
}

