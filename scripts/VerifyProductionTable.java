import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;

class VerifyProductionTable {
    private static class Core extends VerifyPrefixPatch.Core {
        final VerifyPrefixPatch.Bus memory;

        Core(byte[] rom) {
            this(new VerifyPrefixPatch.Bus(rom), rom);
        }

        private Core(VerifyPrefixPatch.Bus bus, byte[] rom) {
            super(bus, 0);
            memory = bus;
            System.arraycopy(rom, 0x8000, memory.ram, 0x4000, 0x4000);
        }

        int run(int pc, int a) {
            regPC = pc;
            regAF = a << 8;
            regSP = 0xc17b;
            memory.ram[regSP] = 0;
            memory.ram[regSP + 1] = (byte) 0xc0;
            for (int steps = 0; regPC != 0xc000; steps++) {
                if (steps == 5000) throw new AssertionError("Call timed out");
                execute(1);
            }
            if (regSP != 0xc17d) throw new AssertionError("Unexpected SP");
            return regAF >>> 8;
        }

        void setup(int type, int left, int right) {
            run(0x209c, type);
            memory.ram[0xc4dd] = (byte) left;
            memory.ram[0xc4de] = (byte) right;
            Arrays.fill(memory.ram, 0xc4f5, 0xc517, (byte) 0xa5);
        }

        byte[] table() {
            return Arrays.copyOfRange(memory.ram, 0xc4f5, 0xc517);
        }
    }

    public static void main(String[] args) throws Exception {
        byte[] rom = Files.readAllBytes(Path.of(args[0]));
        var original = new Core(VerifyPrefixPatch.apply(rom, args[1]));
        var trial = new Core(VerifyPrefixPatch.apply(rom, args[2]));
        int cases = 0;
        for (int own = 0; own < 0x30; own += 2) {
            for (int opponent = 0; opponent < 0x30; opponent++) {
                for (int left : new int[]{0, 1, 0x40, 0x41, 0x80, 0x81, 0xc0, 0xc1}) {
                    for (int right : new int[]{0, 1, 0x40, 0x41, 0x80, 0x81, 0xc0, 0xc1}) {
                        original.setup(own, left, right);
                        trial.setup(own, left, right);
                        byte[] initial = trial.table();
                        int expected = original.run(0x20bf, opponent);
                        int actual = trial.run(0x7f93, opponent);
                        byte[] expectedTable = opponent < 0x2e ? initial : original.table();
                        if (expected != actual || !Arrays.equals(expectedTable, trial.table())) {
                            throw new AssertionError(String.format("Mismatch: %02x %02x %02x %02x",
                                own, opponent, left, right));
                        }
                        cases++;
                    }
                }
            }
        }
        System.out.printf("Matched %d scoring cases and verified row-end scratch contents%n", cases);
    }
}
