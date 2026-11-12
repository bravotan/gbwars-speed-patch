import components.cpu.LR35902;
import components.cpu.MemoryMap;
import components.cpu.PortMap;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Random;

class VerifyPrefixPatch {
    static final int START = 0xd402, END = 0xd7e2;
    static final int[] OFFSETS = {-32, -31, -30, -1, 1, 30, 31, 32};
    record Call(int af, int bc, int de, int hl) {}
    record Result(byte[] table, List<Call> calls, Call registers, int sp, long cycles) {}

    static class Bus implements MemoryMap, PortMap {
        final byte[] ram = new byte[65536];
        final int[] high = new int[256];
        Bus(byte[] rom) {
            System.arraycopy(rom, 0, ram, 0, 0x4000);
        }
        public int getMemoryLength() { return 65536; }
        public int readByte(int address, int cycles) { return address >= 0xff00 ? high[address & 255] : ram[address] & 255; }
        public void writeByte(int address, int data, int cycles) { if (address >= 0xff00) high[address & 255] = data; else ram[address] = (byte) data; }
        public int readPort(int port, int cycles) { return 0; }
        public void writePort(int port, int data, int cycles) {}
    }

    static class Core extends LR35902 {
        Core(Bus bus, int seed) {
            super(false, null, bus, bus, bus.high);
            reset();
            regPC = 0x22bb;
            regSP = 0xc17b;
            regHL = seed;
            regBC = 0x9a01;
            regDE = 0x66a5;
        }
        void startRoutine() { regPC = 0x2270; }
        Call registers() { return new Call(regAF & 0xff53, regBC, regDE, regHL); }
        void runNeighborStub(Bus bus, int[][] edges) {
            int index = regHL - 1 - START;
            int cost = bus.high[0xc1];
            if (index >= 0 && index < edges.length) {
                for (int k = 0; k < OFFSETS.length; k++) {
                    int target = index + OFFSETS[k];
                    int candidate = cost + edges[index][k];
                    if (target >= 0 && target < edges.length && candidate <= bus.high[0xc2]
                        && candidate < (bus.ram[START + target] & 255)) {
                        bus.ram[START + target] = (byte) candidate;
                    }
                }
            }
            regAF = 0x2840;
            regDE = 0x5151;
            regPC = (bus.ram[regSP] & 255) | ((bus.ram[regSP + 1] & 255) << 8);
            regSP += 2;
        }
    }

    static Result run(byte[] rom, int seed, int limit, int[][] edges) {
        Bus bus = new Bus(rom);
        Arrays.fill(bus.ram, START, END, (byte) 255);
        bus.ram[END] = 127;
        if (seed >= START && seed < END) bus.ram[seed] = 0;
        Core cpu = new Core(bus, seed);
        bus.high[0xc2] = limit;
        List<Call> calls = new ArrayList<>();
        int steps = 0;
        long cycles = 0;
        while (cpu.getPC() != 0x22d7) {
            if (++steps > 1_000_000) throw new AssertionError("Loop did not finish");
            if (cpu.getPC() == 0x22d8) {
                calls.add(cpu.registers());
                cpu.runNeighborStub(bus, edges);
            } else {
                cycles += cpu.execute(1);
            }
        }
        return new Result(Arrays.copyOfRange(bus.ram, START, END + 1), calls, cpu.registers(), cpu.getSP(), cycles);
    }

    static Result runReal(byte[] rom, byte[] wram, int mode, int side, int limit) {
        Bus bus = new Bus(rom);
        System.arraycopy(wram, 0, bus.ram, 0xc000, wram.length);
        Core cpu = new Core(bus, START);
        cpu.startRoutine();
        bus.high[0xc5] = mode;
        bus.high[0xd6] = side;
        bus.ram[0xc4dc] = bus.ram[0xc4ec] = (byte) limit;
        List<Call> calls = new ArrayList<>();
        long cycles = 0;
        int steps = 0;
        while (cpu.getPC() != 0x22d7) {
            if (++steps > 2_000_000) throw new AssertionError("Real loop did not finish at " + Integer.toHexString(cpu.getPC()));
            if (cpu.getPC() == 0x22d8) calls.add(cpu.registers());
            cycles += cpu.execute(1);
        }
        return new Result(Arrays.copyOfRange(bus.ram, START, END + 1), calls, cpu.registers(), cpu.getSP(), cycles);
    }

    static byte[] apply(byte[] rom, String path) throws Exception {
        byte[] patch = Files.readAllBytes(Path.of(path));
        if (!new String(patch, 0, 5).equals("PATCH")) throw new AssertionError();
        byte[] result = rom.clone();
        for (int i = 5; i < patch.length - 3;) {
            int offset = ((patch[i] & 255) << 16) | ((patch[i+1] & 255) << 8) | (patch[i+2] & 255);
            int length = ((patch[i+3] & 255) << 8) | (patch[i+4] & 255);
            System.arraycopy(patch, i + 5, result, offset, length);
            i += 5 + length;
        }
        return result;
    }

    public static void main(String[] args) throws Exception {
        byte[] rom = Files.readAllBytes(Path.of(args[0]));
        byte[] v2 = apply(rom, args[1]), trial = apply(rom, args[2]);
        Random random = new Random(2270);
        int cases = 250;
        int faster = 0;
        for (int i = 0; i < cases; i++) {
            int[][] edges = new int[END - START][OFFSETS.length];
            for (int[] row : edges) for (int j = 0; j < row.length; j++) row[j] = random.nextInt(10);
            int seed = switch (i) {
                case 0 -> START;
                case 1 -> END - 1;
                case 2 -> START - 1;
                case 3 -> END;
                default -> START + random.nextInt(END - START);
            };
            int limit = i % 9 == 0 ? 128 : 1 + random.nextInt(32);
            Result a = run(v2, seed, limit, edges), b = run(trial, seed, limit, edges);
            if (!Arrays.equals(a.table, b.table) || !a.calls.equals(b.calls)
                || !a.registers.equals(b.registers) || a.sp != b.sp) {
                System.out.println("table mismatch at " + Arrays.mismatch(a.table, b.table)
                    + ", calls " + a.calls.size() + "/" + b.calls.size() + ", SP " + a.sp + "/" + b.sp);
                for (int j=0; j<Math.min(a.calls.size(), b.calls.size()); j++) {
                    if (!a.calls.get(j).equals(b.calls.get(j))) {
                        System.out.println("First callback diff " + j + " " + a.calls.get(j) + " " + b.calls.get(j));
                        break;
                    }
                }
                throw new AssertionError("Mismatch on case " + i + ": " + a.registers + " vs " + b.registers);
            }
            if (b.cycles < a.cycles) faster++;
        }
        System.out.println(cases + " ROM-byte CPU tests matched cost table, callback order/registers, and final registers/SP.");
        System.out.println("Fewer simulated scan cycles in " + faster + "/" + cases + " synthetic cases.");
        byte[] wram = Files.readAllBytes(Path.of(args[3]));
        for (int mode = 0; mode < 2; mode++) {
            for (int side = 0; side < 2; side++) {
                for (int limit : new int[] {1, 5, 16, 64}) {
                    Result a = runReal(v2, wram, mode, side, limit);
                    Result b = runReal(trial, wram, mode, side, limit);
                    if (!Arrays.equals(a.table, b.table) || !a.calls.equals(b.calls)
                        || !a.registers.equals(b.registers) || a.sp != b.sp) {
                        throw new AssertionError("Real routine mismatch");
                    }
                }
            }
        }
        System.out.println("16 isolated runs of the actual routine using the saved WRAM also matched.");
    }
}
