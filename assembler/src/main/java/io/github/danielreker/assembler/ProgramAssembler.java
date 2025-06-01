package io.github.danielreker.assembler;

import java.io.*;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

public class ProgramAssembler {

    // Custom exception for assembler errors
    static class AssemblyException extends RuntimeException {
        public AssemblyException(String message, int lineNumber, String lineContent) {
            super(String.format("%s (at line %d: \"%s\")", message, lineNumber, lineContent.trim()));
        }
    }

    // Represents the definition of an ISA instruction
    private static class InstructionDefinition {
        final String mnemonicRegex; // Regex to match the instruction and capture operands
        final byte opcode;
        final int numOperandBytes;
        final OperandAssembler assembler;

        @FunctionalInterface
        interface OperandAssembler {
            // Takes the Matcher from mnemonicRegex, symbol table, current address of OPERAND, original line
            // Returns the byte(s) for the operand.
            byte[] assemble(Matcher matcher, Map<String, Integer> symbolTable, int operandAddress, String originalLine);
        }

        InstructionDefinition(String mnemonicRegex, int opcode, int numOperandBytes, OperandAssembler assembler) {
            this.mnemonicRegex = "^" + mnemonicRegex + "$"; // Anchor regex
            this.opcode = (byte) opcode;
            this.numOperandBytes = numOperandBytes;
            this.assembler = assembler;
        }

        public int getSize() {
            return 1 + numOperandBytes;
        }
    }

    private final Map<Pattern, InstructionDefinition> instructionPatterns = new LinkedHashMap<>();
    private final Map<String, Integer> symbolTable = new HashMap<>();
    // TreeMap ensures assembled bytes are stored by address and can be iterated in order
    private final TreeMap<Integer, Byte> assembledCode = new TreeMap<>();

    public ProgramAssembler() {
        defineInstructions();
    }

    private void defineInstructions() {
        // Instructions with NO OPERANDS (Opcode only)
        addInstruction("NOP",    0x01, 0, (m, s, ca, ol) -> new byte[0]);
        addInstruction("OUTPUT", 0x03, 0, (m, s, ca, ol) -> new byte[0]);
        addInstruction("INC\\s+A", 0x08, 0, (m, s, ca, ol) -> new byte[0]); // INC A
        addInstruction("MOV\\s+B\\s*,\\s*A", 0x09, 0, (m, s, ca, ol) -> new byte[0]); // MOV B, A
        addInstruction("ADD\\s+A\\s*,\\s*B", 0x0A, 0, (m, s, ca, ol) -> new byte[0]); // ADD A, B (uses microcode 0xA and 0xB)
        addInstruction("HALT",   0x0C, 0, (m, s, ca, ol) -> new byte[0]);
        addInstruction("PUSH\\s+A",0x0F, 0, (m, s, ca, ol) -> new byte[0]); // PUSH A (uses microcode 0xF, 0x10, 0x11, 0x12)
        addInstruction("POP\\s+A", 0x13, 0, (m, s, ca, ol) -> new byte[0]);  // POP A (uses microcode 0x13, 0x14, 0x15)
        addInstruction("INC\\s+B", 0x24, 0, (m, s, ca, ol) -> new byte[0]);
        addInstruction("DEC\\s+A", 0x25, 0, (m, s, ca, ol) -> new byte[0]);
        addInstruction("DEC\\s+B", 0x26, 0, (m, s, ca, ol) -> new byte[0]);
        addInstruction("ADD\\s+B\\s*,\\s*A", 0x27, 0, (m, s, ca, ol) -> new byte[0]); // ADD B, A (uses microcode 0x27, 0x28)
        addInstruction("SUB\\s+A\\s*,\\s*B", 0x29, 0, (m, s, ca, ol) -> new byte[0]); // SUB A, B (uses microcode 0x29, 0x2A)
        addInstruction("SUB\\s+B\\s*,\\s*A", 0x2B, 0, (m, s, ca, ol) -> new byte[0]); // SUB B, A (uses microcode 0x2B, 0x2C)
        addInstruction("SWAP\\s+A\\s*,\\s*B",0x2D,0, (m, s, ca, ol) -> new byte[0]); // SWAP A,B (uses microcode 0x2D, 0x2E, 0x2F)
        addInstruction("INPUT",0x02, 0, (m,s,ca,ol) -> new byte[0]);

        // Instructions with ONE BYTE OPERAND (immediate value or label)
        InstructionDefinition.OperandAssembler singleByteOperandParser =
                (matcher, symTable, operandAddr, line) -> // operandAddr is the address where this byte will go
                        new byte[]{parseByteOperand(matcher.group(1), symTable, line)};

        addInstruction("JMP\\s+([\\w#$0-9xA-Fa-f]+)", 0x04, 1, singleByteOperandParser);
        addInstruction("MOV\\s+A\\s*,\\s*([\\w#$0-9xA-Fa-f]+)", 0x06, 1, singleByteOperandParser);
        addInstruction("MOV\\s+SP\\s*,\\s*([\\w#$0-9xA-Fa-f]+)", 0x0D, 1, singleByteOperandParser);
        // For MOV A, [#addr] or MOV A, [label] - the operand is the address/label itself
        addInstruction("MOV\\s+A\\s*,\\s*\\[\\s*([\\w#$0-9xA-Fa-f]+)\\s*\\]", 0x16, 1, singleByteOperandParser);
        addInstruction("JZ\\s+([\\w#$0-9xA-Fa-f]+)", 0x1A, 1, singleByteOperandParser);
        // For MOV [#addr], A or MOV [label], A - the operand is the address/label
        addInstruction("MOV\\s+\\[\\s*([\\w#$0-9xA-Fa-f]+)\\s*\\]\\s*,\\s*A", 0x20, 1, singleByteOperandParser);
        addInstruction("MOV\\s+B\\s*,\\s*([\\w#$0-9xA-Fa-f]+)", 0x30, 1, singleByteOperandParser);
    }

    private void addInstruction(String mnemonicRegex, int opcode, int operandBytes, InstructionDefinition.OperandAssembler assembler) {
        Pattern pattern = Pattern.compile(mnemonicRegex, Pattern.CASE_INSENSITIVE);
        instructionPatterns.put(pattern, new InstructionDefinition(mnemonicRegex, opcode, operandBytes, assembler));
    }

    private InstructionDefinition findInstructionDefinition(String instructionPart) {
        for (Map.Entry<Pattern, InstructionDefinition> entry : instructionPatterns.entrySet()) {
            Matcher matcher = entry.getKey().matcher(instructionPart);
            if (matcher.matches()) {
                return entry.getValue();
            }
        }
        return null;
    }

    private Matcher getInstructionMatcher(String instructionPart) {
        for (Pattern pattern : instructionPatterns.keySet()) {
            Matcher matcher = pattern.matcher(instructionPart);
            if (matcher.matches()) {
                return matcher;
            }
        }
        return null;
    }

    public static byte parseByteOperand(String operandStr, Map<String, Integer> symbolTable, String originalLine) {
        operandStr = operandStr.trim();
        if (operandStr.startsWith("#")) { // Allow # for immediate, although not strictly needed if parsing context is clear
            operandStr = operandStr.substring(1);
        }

        try {
            if (operandStr.toLowerCase().startsWith("0x")) {
                int val = Integer.parseInt(operandStr.substring(2), 16);
                if (val < -128 || val > 255) throw new AssemblyException("Hex operand '" + operandStr + "' out of 8-bit range", 0, originalLine);
                return (byte) val;
            } else if (Character.isDigit(operandStr.charAt(0)) || (operandStr.startsWith("-") && operandStr.length() > 1 && Character.isDigit(operandStr.charAt(1)))) {
                int val = Integer.parseInt(operandStr);
                if (val < -128 || val > 255) throw new AssemblyException("Decimal operand '" + operandStr + "' out of 8-bit range",0, originalLine);
                return (byte) val;
            } else { // Label
                Integer targetAddress = symbolTable.get(operandStr.toLowerCase()); // Store/lookup labels case-insensitively
                if (targetAddress == null) {
                    // This will be an error in Pass 2 if still not found.
                    throw new AssemblyException("Undefined label '" + operandStr + "'", 0, originalLine);
                }
                if (targetAddress < 0 || targetAddress > 0xFF) {
                    throw new AssemblyException("Label '" + operandStr + "' value (0x" + Integer.toHexString(targetAddress) + ") out of 8-bit range for direct operand usage", 0, originalLine);
                }
                return (byte) (targetAddress & 0xFF);
            }
        } catch (NumberFormatException e) {
            throw new AssemblyException("Invalid number format for operand '" + operandStr + "'",0, originalLine);
        }
    }

    public void performPass1(List<String> lines) {
        symbolTable.clear();
        int locationCounter = 0;
        System.out.println("--- Pass 1: Symbol Table Construction ---");

        for (int i = 0; i < lines.size(); i++) {
            String originalLine = lines.get(i);
            String line = originalLine.split(";", 2)[0].trim();

            if (line.isEmpty()) continue;

            // Check for label
            Matcher labelMatcher = Pattern.compile("^([a-zA-Z_][a-zA-Z_0-9]*):").matcher(line);
            if (labelMatcher.find()) {
                String label = labelMatcher.group(1).toLowerCase(); // Store labels case-insensitively
                line = line.substring(labelMatcher.end()).trim();
                if (symbolTable.containsKey(label)) {
                    throw new AssemblyException("Duplicate label definition", i + 1, originalLine);
                }
                symbolTable.put(label, locationCounter);
                System.out.printf("  Label '%s' defined at 0x%02X%n", label, locationCounter);
            }

            line = line.trim();
            if (line.isEmpty()) continue;

            String mnemonicCandidate = line.split("\\s+")[0].toUpperCase();
            InstructionDefinition def = findInstructionDefinition(line);

            if (def != null) {
                locationCounter += def.getSize();
            } else if (mnemonicCandidate.equalsIgnoreCase("DB")) {
                String operandsPart = line.substring(2).trim();
                if (operandsPart.isEmpty()) {
                    throw new AssemblyException("DB directive requires operands", i + 1, originalLine);
                }
                // Count number of operands, assuming they are comma-separated
                locationCounter += operandsPart.split(",").length;
            } else {
                throw new AssemblyException("Unknown mnemonic '" + mnemonicCandidate + "'", i + 1, originalLine);
            }
        }
        System.out.println("--- Pass 1 Complete. Symbol Table: ---");
        symbolTable.forEach((k, v) -> System.out.printf("  %-10s: 0x%02X (%d)%n", k, v, v));
        System.out.println();
    }

    public void performPass2(List<String> lines) {
        assembledCode.clear();
        int locationCounter = 0;
        System.out.println("--- Pass 2: Code Generation ---");

        for (int i = 0; i < lines.size(); i++) {
            String originalLine = lines.get(i);
            String line = originalLine.split(";", 2)[0].trim();

            if (line.isEmpty()) continue;

            if (line.contains(":")) { // Also handles labels on their own line due to line.trim() below
                line = line.substring(line.indexOf(":") + 1).trim();
            }
            line = line.trim();
            if (line.isEmpty()) continue;

            String mnemonicCandidate = line.split("\\s+")[0].toUpperCase();
            InstructionDefinition def = findInstructionDefinition(line);
            Matcher matcher = getInstructionMatcher(line);


            byte[] operandBytes;
            int currentInstructionLineNumber = i + 1; // For error reporting

            if (def != null && matcher != null) {
                System.out.printf("0x%02X: Assembling '%s' -> ", locationCounter, originalLine.trim());

                // 1. Add the opcode first
                assembledCode.put(locationCounter, def.opcode);
                System.out.printf("%02X ", def.opcode & 0xFF);
                locationCounter++;


                // 2. Assemble and add operand bytes
                try {
                    operandBytes = def.assembler.assemble(matcher, symbolTable, locationCounter, originalLine);
                } catch (AssemblyException e) { // Re-throw with line number if parseByteOperand failed
                    throw new AssemblyException(e.getMessage(), currentInstructionLineNumber, originalLine);
                }
            } else if (mnemonicCandidate.equalsIgnoreCase("DB")) {
                System.out.printf("0x%02X: Assembling '%s' -> ", locationCounter, originalLine.trim());
                String operandsPart = line.substring(2).trim();
                String[] operandStrings = operandsPart.split(",");
                operandBytes = new byte[operandStrings.length];
                for (int j = 0; j < operandStrings.length; j++) {
                    try {
                        operandBytes[j] = parseByteOperand(operandStrings[j].trim(), symbolTable, originalLine);
                    } catch (AssemblyException e) {
                        throw new AssemblyException(e.getMessage(), currentInstructionLineNumber, originalLine);
                    }
                }
            } else {
                throw new AssemblyException("Unknown mnemonic or bad format '" + line + "'", currentInstructionLineNumber, originalLine);
            }

            // Add operand bytes (if any)
            for (byte b : operandBytes) {
                System.out.printf("%02X ", b & 0xFF);
                assembledCode.put(locationCounter++, b);
            }
            System.out.println();
        }
        System.out.println("--- Pass 2 Complete ---\n");
    }

    public void writeBinaryOutput(String filePath) throws IOException {
        if (assembledCode.isEmpty()) {
            new FileOutputStream(filePath).close();
            return;
        }
        int maxAddress = assembledCode.lastKey(); // Get the highest address used
        byte[] binaryData = new byte[maxAddress + 1]; // Create array up to that address
        for (Map.Entry<Integer, Byte> entry : assembledCode.entrySet()) {
            if(entry.getKey() < binaryData.length) { // Ensure we don't go out of bounds
                binaryData[entry.getKey()] = entry.getValue();
            }
        }
        try (FileOutputStream fos = new FileOutputStream(filePath)) {
            fos.write(binaryData);
        }
    }

    public void writeLogisimImage(String filePath, int wordSizeBits) throws IOException {
        if (wordSizeBits % 8 != 0) {
            throw new IllegalArgumentException("Logisim word size must be a multiple of 8 bits.");
        }
        int bytesPerWord = wordSizeBits / 8;

        try (PrintWriter writer = new PrintWriter(new FileWriter(filePath))) {
            writer.print("v2.0 raw\n");

            if (assembledCode.isEmpty()) {
                if (bytesPerWord > 0) writer.print("0");
                writer.println();
                return;
            }

            List<String> logisimWords = new ArrayList<>();
            int lastNonZeroWordIndex = -1; // Index in the logisimWords list

            int maxAssembledAddress = assembledCode.lastKey();
            int totalWords = (maxAssembledAddress / bytesPerWord) + 1;
            if (maxAssembledAddress == -1 && bytesPerWord > 0) totalWords = 1; // Handle case of only a DB 0 at address 0


            for (int wordIdx = 0; wordIdx < totalWords; wordIdx++) {
                long currentWordValue = 0;
                boolean thisWordHasContent = false; // True if any byte of this word was in assembledCode

                for (int byteInWord = 0; byteInWord < bytesPerWord; byteInWord++) {
                    int currentByteAddress = wordIdx * bytesPerWord + byteInWord;
                    byte byteVal = assembledCode.getOrDefault(currentByteAddress, (byte) 0);
                    currentWordValue = (currentWordValue << 8) | (byteVal & 0xFF);
                    if (assembledCode.containsKey(currentByteAddress) && byteVal != 0) { // Check if it was explicitly assembled and non-zero
                        thisWordHasContent = true;
                    } else if (assembledCode.containsKey(currentByteAddress) && byteVal == 0) { // If it was a DB 0
                        thisWordHasContent = true;
                    }
                }
                logisimWords.add(Long.toHexString(currentWordValue));
                if (thisWordHasContent) {
                    lastNonZeroWordIndex = wordIdx;
                }
            }

            if (lastNonZeroWordIndex == -1) { // All words are zero (or no words)
                if (!logisimWords.isEmpty()) { // If there was at least one (zero) word
                    writer.print("0");
                }
            } else {
                for (int i = 0; i <= lastNonZeroWordIndex; i++) {
                    writer.print(logisimWords.get(i));
                    if (i < lastNonZeroWordIndex) {
                        writer.print(" ");
                    }
                }
            }
            writer.println();
        }
    }

    public static void main(String[] args) {
        String inputFile = "program.asm"; // Default input
        if (args.length > 0) {
            inputFile = args[0];
        }
        String baseName = inputFile.contains(".") ? inputFile.substring(0, inputFile.lastIndexOf('.')) : inputFile;
        String outputFileBin = baseName + ".bin";
        String outputFileLogisim = baseName + ".logisimimg";

        int logisimProgramMemoryWordSizeBits = 8;

        ProgramAssembler assembler = new ProgramAssembler();
        try {
            List<String> lines = Files.lines(Paths.get(inputFile))
                    .map(String::trim) // Trim leading/trailing whitespace from all lines
                    .collect(Collectors.toList());

            System.out.println("Assembling: " + inputFile);
            assembler.performPass1(lines);
            assembler.performPass2(lines);

            assembler.writeBinaryOutput(outputFileBin);
            System.out.println("Binary output written to: " + outputFileBin);

            assembler.writeLogisimImage(outputFileLogisim, logisimProgramMemoryWordSizeBits);
            System.out.println("Logisim image output written to: " + outputFileLogisim);

            System.out.println("\nAssembly successful!");

        } catch (IOException e) {
            System.err.println("File I/O Error: " + e.getMessage());
            e.printStackTrace();
        } catch (AssemblyException e) {
            System.err.println("Assembly Error: " + e.getMessage());
        } catch (Exception e) {
            System.err.println("An unexpected error occurred:");
            e.printStackTrace();
        }
    }
}