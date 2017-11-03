package net.simon987.server.assembly;

import net.simon987.server.GameServer;
import net.simon987.server.ServerConfiguration;
import net.simon987.server.assembly.exception.CancelledException;
import net.simon987.server.assembly.instruction.*;
import net.simon987.server.event.CpuInitialisationEvent;
import net.simon987.server.event.GameEvent;
import net.simon987.server.io.JSONSerialisable;
import net.simon987.server.logging.LogManager;
import net.simon987.server.user.User;
import org.json.simple.JSONArray;
import org.json.simple.JSONObject;

import java.util.ArrayList;
import java.util.HashMap;

/**
 * CPU: Central Processing Unit. A CPU is capable of reading bytes from
 * a Memory object and execute them. A CPU object holds registers objects &
 * a Memory object.
 */
public class CPU implements JSONSerialisable{

    /**
     *
     */
    private Status status;

    /**
     * Memory associated with the CPU, 64kb max
     */
    private Memory memory;

    /**
     * set of instructions of this CPU
     */
    private InstructionSet instructionSet;

    /**
     * set of registers of this CPU
     */
    private RegisterSet registerSet;

    /**
     * Offset of the code segment. The code starts to get
     * executed at this address each tick. Defaults to 0x4000
     */
    private int codeSegmentOffset;

    /**
     * Instruction pointer, always points to the next instruction
     */
    private int ip;

    /**
     * List of attached hardware, 'modules'
     */
    private HashMap<Integer, CpuHardware> attachedHardware;

    private ServerConfiguration config;

    private long timeout;

    private int registerSetSize;

    /**
     * Creates a new CPU
     */
    public CPU(ServerConfiguration config, User user) throws CancelledException{
        this.config = config;
        instructionSet = new DefaultInstructionSet();
        registerSet = new DefaultRegisterSet();
        attachedHardware = new HashMap<>();
        codeSegmentOffset = config.getInt("org_offset");

        timeout = config.getInt("user_timeout");

        instructionSet.add(new JmpInstruction(this));
        instructionSet.add(new JnzInstruction(this));
        instructionSet.add(new JzInstruction(this));
        instructionSet.add(new JgInstruction(this));
        instructionSet.add(new JgeInstruction(this));
        instructionSet.add(new JleInstruction(this));
        instructionSet.add(new JlInstruction(this));
        instructionSet.add(new PushInstruction(this));
        instructionSet.add(new PopInstruction(this));
        instructionSet.add(new CallInstruction(this));
        instructionSet.add(new RetInstruction(this));
        instructionSet.add(new MulInstruction(this));
        instructionSet.add(new DivInstruction(this));
        instructionSet.add(new JnsInstruction(this));
        instructionSet.add(new JsInstruction(this));
        instructionSet.add(new HwiInstruction(this));

        status = new Status();
        memory = new Memory(config.getInt("memory_size"));

        GameEvent event = new CpuInitialisationEvent(this, user);
        GameServer.INSTANCE.getEventDispatcher().dispatch(event);
        if(event.isCancelled()){
            throw new CancelledException();
        }
    }

    public void reset() {
        status.clear();
        registerSet.getRegister("SP").setValue(config.getInt("stack_bottom"));
        registerSet.getRegister("BP").setValue(config.getInt("stack_bottom"));
        ip = codeSegmentOffset;
    }

    public void execute() {

        long startTime = System.currentTimeMillis();
        int counter = 0;
        status.clear();

        registerSetSize = registerSet.size();

        // status.breakFlag = true;
        while (!status.isBreakFlag()) {
            counter++;

            if(counter % 1000 == 0){
                if (System.currentTimeMillis() >= (startTime + timeout)) {
                    System.out.println("CPU Timeout " + this + " after " + counter + "instructions (" + timeout + "ms): " + (double)counter/((double)timeout/1000)/1000000 + "MHz");
                    return;
                }
            }

            //fetch instruction
            int machineCode = memory.get(ip);

            /*
             * Contents of machineCode should look like this:
             * SSSS SDDD DDOO OOOO
             * Where S is source, D is destination and O is the opCode
             */
            Instruction instruction = instructionSet.get(machineCode & 0x03F); // 0000 0000 00XX XXXX

            int source = (machineCode >> 11) & 0x001F; // XXXX X000 0000 0000
            int destination = (machineCode >> 6) & 0x001F; // 0000 0XXX XX00 0000

            executeInstruction(instruction, source, destination);
//            LogManager.LOGGER.info(instruction.getMnemonic());
        }
        double elapsed = (System.currentTimeMillis() - startTime);
        System.out.println("----------\n" + counter + " instruction in " + elapsed + "ms : " + (double)counter/(elapsed/1000)/1000000 + "MHz");
    }

    public void executeInstruction(Instruction instruction, int source, int destination) {


        //Execute the instruction
        if (source == 0) {
            //No operand (assuming that destination is also null)
            ip++;
            instruction.execute(status);
        } else if (source == Operand.IMMEDIATE_VALUE) {
            ip++;
            int sourceValue = memory.get(ip);

            if (destination == 0) {
                //Single operand
                ip++;
                instruction.execute(sourceValue, status);
            } else if (destination == Operand.IMMEDIATE_VALUE) {
                //Destination is an immediate value too
                //this shouldn't happen
                LogManager.LOGGER.severe("Trying to execute an instruction with 2" +
                        "immediate values as operands"); //todo remove debug info

            } else if (destination == Operand.IMMEDIATE_VALUE_MEM) {
                //Destination is memory immediate
                ip += 2;
                instruction.execute(memory, memory.get(ip - 1), sourceValue, status);
            } else if (destination <= registerSetSize) {
                //Destination is a register
                ip++;
                instruction.execute(registerSet, destination, sourceValue, status);

            } else if (destination <= registerSetSize * 2) {
                //Destination is [reg]
                ip++;
                instruction.execute(memory, registerSet.get(destination - registerSetSize), sourceValue, status);
            } else {
                //Assuming that destination is [reg + x]
                ip += 2;
                instruction.execute(memory, registerSet.get(destination - registerSetSize - registerSetSize) + memory.get(ip - 1),
                        sourceValue, status);
            }

        } else if (source == Operand.IMMEDIATE_VALUE_MEM) {
            //Source is [x]
            ip++;
            int sourceValue = memory.get(memory.get(ip));

            if (destination == 0) {
                //Single operand
                ip++;
                instruction.execute(sourceValue, status);
                instruction.execute(memory, memory.get(ip - 1), status); //For POP instruction
            } else if (destination == Operand.IMMEDIATE_VALUE) {
                //Destination is an immediate value

                //this shouldn't happen
                LogManager.LOGGER.severe("Trying to execute an instruction with an" +
                        "immediate values as dst operand"); //todo remove debug info
            } else if (destination == Operand.IMMEDIATE_VALUE_MEM) {
                //Destination is memory immediate too
                ip += 2;
                instruction.execute(memory, memory.get(ip - 1), sourceValue, status);
            } else if (destination <= registerSetSize) {
                //Destination is a register
                ip++;
                instruction.execute(registerSet, destination, sourceValue, status);
            } else if (destination <= registerSetSize * 2) {
                //Destination is [reg]
                ip++;
                instruction.execute(memory, registerSet.get(destination - registerSetSize), memory, sourceValue, status);
            } else {
                //Assuming that destination is [reg + x]
                ip += 2;
                instruction.execute(memory, registerSet.get(destination - registerSetSize - registerSetSize) + memory.get(ip - 1), sourceValue, status);
            }

        } else if (source <= registerSetSize) {
            //Source is a register

            if (destination == 0) {
                //Single operand
                ip++;
                instruction.execute(registerSet, source, status);

            } else if (destination == Operand.IMMEDIATE_VALUE) {
                //Destination is an immediate value
                //this shouldn't happen
                LogManager.LOGGER.severe("Trying to execute an instruction with an" +
                        "immediate values as dst operand"); //todo remove debug info
            } else if (destination == Operand.IMMEDIATE_VALUE_MEM) {
                //Destination is memory immediate
                ip += 2;
                instruction.execute(memory, memory.get(ip - 1), registerSet, source, status);
            } else if (destination <= registerSetSize) {
                //Destination is a register too
                ip++;
                instruction.execute(registerSet, destination, registerSet, source, status);
            } else if (destination <= registerSetSize * 2) {
                //Destination is [reg]
                ip++;
                instruction.execute(memory, registerSet.get(destination - registerSetSize), registerSet, source, status);
            } else {
                //Assuming that destination is [reg + x]
                ip += 2;
                instruction.execute(memory, registerSet.get(destination - registerSetSize - registerSetSize) + memory.get(ip - 1),
                        registerSet, source, status);
            }

        } else if (source <= registerSetSize * 2) {
            //Source is [reg]
            if (destination == 0) {
                //Single operand
                ip++;
                instruction.execute(memory, registerSet.get(source), status);
            } else if (destination == Operand.IMMEDIATE_VALUE) {
                //Destination is an immediate value
                //this shouldn't happen
                LogManager.LOGGER.severe("Trying to execute an instruction with an" +
                        "immediate values as dst operand"); //todo remove debug info
            } else if (destination == Operand.IMMEDIATE_VALUE_MEM) {
                //Destination is an memory immediate
                ip++;
                instruction.execute(memory, memory.get(ip++), memory, registerSet.get(source - registerSetSize), status);
            } else if (destination <= registerSetSize) {
                //Destination is a register
                ip++;
                instruction.execute(registerSet, destination, memory, registerSet.get(source), status);
            } else if (destination <= registerSetSize * 2) {
                //Destination is [reg]
                ip++;
                instruction.execute(memory, registerSet.get(destination - registerSetSize), memory, registerSet.get(source), status);
            } else {
                //Assuming that destination is [reg + x]
                ip += 2;
                instruction.execute(memory, registerSet.get(destination - registerSetSize - registerSetSize) + memory.get(ip - 1),
                        memory, registerSet.get(source - registerSetSize), status);
            }
        } else {
            //Assuming that source is [reg + X]

            ip++;
            int sourceDisp = memory.get(ip);

            if (destination == 0) {
                //Single operand
                ip += 1;
                instruction.execute(memory, registerSet.get(source - registerSetSize - registerSetSize) + memory.get(ip - 1), status);

            } else if (destination == Operand.IMMEDIATE_VALUE) {
                //Destination is an immediate value
                //this shouldn't happen
                LogManager.LOGGER.severe("Trying to execute an instruction with an" +
                        "immediate values as dst operand"); //todo remove debug info
            } else if (destination == Operand.IMMEDIATE_VALUE_MEM) {
                //Destination is memory immediate
                ip += 2;
                instruction.execute(memory, memory.get(ip - 1), memory,
                        registerSet.get(source - registerSetSize - registerSetSize) + sourceDisp, status);
            } else if (destination < registerSetSize) {
                //Destination is a register
                ip++;
                instruction.execute(registerSet, destination, memory,
                        registerSet.get(source - registerSetSize - registerSetSize) + sourceDisp, status);
            } else if (destination <= registerSetSize * 2) {
                //Destination is [reg]
                ip++;
                instruction.execute(memory, registerSet.get(destination - registerSetSize), memory,
                        registerSet.get(source - registerSetSize - registerSetSize) + sourceDisp, status);
            } else {
                //Assuming that destination is [reg + x]
                ip += 2;
                instruction.execute(memory, registerSet.get(destination - registerSetSize - registerSetSize) + memory.get(ip - 1),
                        memory, registerSet.get(source - registerSetSize - registerSetSize) + sourceDisp, status);
            }
        }
    }

    @Override
    public JSONObject serialise() {

        JSONObject json = new JSONObject();

        json.put("memory", memory.serialise());

        json.put("registerSet", registerSet.serialise());
        json.put("codeSegmentOffset", codeSegmentOffset);

        JSONArray hardwareList = new JSONArray();

        for(Integer address : attachedHardware.keySet()){

            CpuHardware hardware = attachedHardware.get(address);

            if(hardware instanceof JSONSerialisable){

                JSONObject serialisedHw = ((JSONSerialisable) hardware).serialise();
                serialisedHw.put("address", address);
                hardwareList.add(serialisedHw);
            }
        }

        json.put("hardware", hardwareList);

        return json;
    }

    public static CPU deserialize(JSONObject json, User user) throws CancelledException {

        CPU cpu = new CPU(GameServer.INSTANCE.getConfig(), user);

        cpu.codeSegmentOffset = (int)(long)json.get("codeSegmentOffset");

        JSONArray hardwareList = (JSONArray)json.get("hardware");

        for(JSONObject serialisedHw : (ArrayList<JSONObject>)hardwareList){
            CpuHardware hw = CpuHardware.deserialize(serialisedHw);
            hw.setCpu(cpu);
            cpu.attachHardware(hw, (int)(long)serialisedHw.get("address"));
        }

        cpu.memory = Memory.deserialize((JSONObject)json.get("memory"));
        cpu.registerSet = RegisterSet.deserialize((JSONObject) json.get("registerSet"));

        return cpu;

    }

    public InstructionSet getInstructionSet() {
        return instructionSet;
    }

    public RegisterSet getRegisterSet() {
        return registerSet;
    }

    public Memory getMemory() {
        return memory;
    }

    public Status getStatus() {
        return status;
    }

    public int getIp() {
        return ip;
    }

    public void setIp(char ip) {
        this.ip = ip;
    }

    public void setCodeSegmentOffset(int codeSegmentOffset) {
        this.codeSegmentOffset = codeSegmentOffset;
    }

    public void attachHardware(CpuHardware hardware, int address){
        attachedHardware.put(address, hardware);
    }

    public void detachHardware(int address){
        attachedHardware.remove(address);
    }

    public boolean hardwareInterrupt(int address){
        CpuHardware hardware = attachedHardware.get(address);

        if(hardware != null){
            hardware.handleInterrupt(status);
            return true;
        } else {
            return false;
        }
    }

    @Override
    public String toString() {

        String str = "------------------------\n";
        str += registerSet.toString();
        str += status.toString();
        str += "------------------------\n";

        return str;
    }
}