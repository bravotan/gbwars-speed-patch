import components.cartridge.GBCartridge;
import components.input.ButtonInputProvider;
import components.video.GBLCDController;
import java.io.File;
import java.nio.file.Path;
import network.Network;
import output.GBDisplayWindow;
import output.GBSoundOutput;
import patching.IPS;
import sstates.gameboy.BESSSaveState;
import system.GameBoy;

class TraceCpuProduction {
    private static final int CYCLES_PER_STEP = 4;

    private static class Display extends GBDisplayWindow {
        Display() { super(null); }
        @Override public void setLCD(GBLCDController controller) {}
        @Override public void renderFrame() {}
        @Override public void renderBlankScreen() {}
        @Override public void renderStopScreen() {}
        @Override public void avoidFrameSkip() {}
        @Override public void drawCurrentLine() {}
        @Override public void updateColor(int index, int value) {}
    }

    private static class Input implements ButtonInputProvider {
        public boolean isButtonDown(int button) { return false; }
        public void setRequireWindowFocus(boolean required) {}
        public void setButtonMap(int[][] map) {}
        public int[] getButtonMap(int player) { return new int[0]; }
        public void addButtonListener(ButtonListener listener) {}
        public void removeButtonListener(ButtonListener listener) {}
    }

    private static class InMemoryCartridge extends GBCartridge {
        InMemoryCartridge(Path rom, IPS patch) throws Exception { super(rom.toString(), patch); }
        @Override protected void writeRAMtoFile() {}
    }

    // B opens the menu, Up wraps to its last item, A confirms. This input
    // sequence was verified with the GB Wars slot 3 state used in this project.
    private static int button(long frame) {
        if (frame >= 2 && frame < 5) return 1;
        if (frame >= 110 && frame < 114) return 6;
        if (frame >= 140 && frame < 144) return 0;
        return -1;
    }

    public static void main(String[] args) throws Exception {
        boolean turbo = args[0].equals("turbo");
        boolean resumeCpuTurn = args.length > 3 && args[3].equals("resume-cpu");
        int sideChange = turbo ? 0x4396 : 0x43e1;
        int commit = turbo ? 0x4067 : 0x4060;
        int productionEntry = turbo ? 0x5e95 : 0x6093;
        int movementSearch = turbo ? 0x1d0c : 0x2270;
        int placementCheck = turbo ? 0x230a : 0x2840;
        int productionDecision = turbo ? 0x5eba : 0x60ba;
        var game = new GameBoy(new Display(), new Input(), new GBSoundOutput("headless", 44100, 60), new Network());
        IPS patch = args.length > 4 ? new IPS(new File(args[4])) : null;
        game.insertCartridge(new InMemoryCartridge(Path.of(args[1]), patch));
        game.setState(BESSSaveState.from(new File(args[2])).getState(), true, true);
        int currentButton = -1;
        int oldPC = -1;
        int oldBank = -1;
        int vblanks = 0;
        int frames = game.getCyclesPerFrame();
        long turnStart = resumeCpuTurn ? 0 : -1;
        if (resumeCpuTurn) {
            System.out.printf("resume pc=%04x bank=%02x side=%d cpu_mask=%d phase=%02x pending=%02x%n",
                game.getPC(), game.getBank1(), game.readByte(0xffd6, 0), game.readByte(0xffd5, 0),
                game.readByte(0xc686, 0), game.readByte(0xc668, 0));
        }
        long productionStart = -1;
        int movementSearches = 0;
        int placementChecks = 0;
        int spIndex = java.util.Arrays.asList(game.getRegisterNames()).indexOf("SP");
        int placementReturnPC = -1;
        int placementReturnSP = -1;
        int placementReturnBank = -1;
        long placementStart = 0;
        long placementCycles = 0;
        var productionPCs = new long[0x10000];
        var scores = java.security.MessageDigest.getInstance("SHA-256");
        int scoreCount = 0;
        int afIndex = java.util.Arrays.asList(game.getRegisterNames()).indexOf("AF");
        var copyCallers = new java.util.TreeMap<String, long[]>();
        long[] activeCopy = null;
        for (long now = 0; now < 10000L * frames;) {
            int requested = resumeCpuTurn ? -1 : button(now / frames);
            if (requested != currentButton) {
                if (currentButton >= 0) game.setButtonDownOverride(currentButton, false);
                if (requested >= 0) game.setButtonDownOverride(requested, true);
                currentButton = requested;
            }
            int pc = game.getPC();
            int bank = pc >= 0x4000 && pc < 0x8000 ? game.getBank1() : 0;
            if (pc != oldPC || bank != oldBank) {
                if (pc == sideChange && bank == 1) {
                    if (turnStart >= 0) {
                        System.out.printf("end vblank=%d cycles=%d%n", vblanks, now - turnStart);
                        java.util.stream.IntStream.range(0, productionPCs.length).boxed()
                            .filter(address -> productionPCs[address] > 0)
                            .sorted(java.util.Comparator.comparingLong((Integer address) -> productionPCs[address]).reversed())
                            .limit(12).forEach(address -> System.out.printf("production_pc=%s:%04x cycles=%d%n",
                                address < 0x8000 ? "00" : "02", address & 0x7fff, productionPCs[address]));
                        var board = java.security.MessageDigest.getInstance("SHA-256");
                        for (int address = 0xc980; address < 0xd402; address++) {
                            board.update((byte) game.readByte(address, 0));
                        }
                        System.out.printf("scores=%d hash=%s board=%s%n", scoreCount,
                            java.util.HexFormat.of().formatHex(scores.digest()),
                            java.util.HexFormat.of().formatHex(board.digest()));
                        copyCallers.forEach((caller, totals) -> System.out.printf(
                            "copy_return=%s calls=%d cycles=%d%n", caller, totals[0], totals[1]));
                        return;
                    }
                    turnStart = now;
                }
                if (turnStart >= 0 && pc == 0x0040) vblanks++;
                if (productionStart >= 0 && pc == placementReturnPC && bank == placementReturnBank
                    && game.getRegisterValue(spIndex) == placementReturnSP) {
                    placementCycles += now - placementStart;
                    placementReturnPC = -1;
                }
                if (productionStart >= 0 && bank == 0) {
                    if (pc == movementSearch) movementSearches++;
                    if (pc == placementCheck) {
                        placementChecks++;
                        int sp = game.getRegisterValue(spIndex);
                        placementReturnPC = game.readByte(sp, 0) | (game.readByte(sp + 1, 0) << 8);
                        placementReturnSP = sp + 2;
                        placementReturnBank = placementReturnPC >= 0x4000 && placementReturnPC < 0x8000
                            ? game.getBank1() : 0;
                        placementStart = now;
                    }
                }
                if (turnStart >= 0 && bank == 2) {
                    if (!turbo && pc == 0x6448 && productionStart >= 0) {
                        scoreCount++;
                        scores.update((byte) game.readByte(0xc680, 0));
                        int opponent = game.readByte(0xc681, 0);
                        scores.update((byte) opponent);
                        scores.update((byte) (game.getRegisterValue(afIndex) >>> 8));
                        if (opponent >= 0x2e) {
                            for (int address = 0xc4f5; address < 0xc517; address++) {
                                scores.update((byte) game.readByte(address, 0));
                            }
                        }
                    }
                    if (pc == productionEntry) {
                        productionStart = now;
                        movementSearches = 0;
                        placementChecks = 0;
                        placementCycles = 0;
                        System.out.printf("production_enter vblank=%d state=%02x%n", vblanks, game.readByte(0xc686, 0));
                    }
                    if (pc == productionDecision) {
                        System.out.printf("production_pick vblank=%d cycles=%d searches=%d placement_checks=%d placement_cycles=%d%n",
                            vblanks, now - productionStart, movementSearches, placementChecks, placementCycles);
                        productionStart = -1;
                    }
                    if (pc == commit) {
                        if (productionStart >= 0) {
                            System.out.printf("production_stop vblank=%d cycles=%d searches=%d placement_checks=%d placement_cycles=%d%n",
                                vblanks, now - productionStart, movementSearches, placementChecks, placementCycles);
                            productionStart = -1;
                        }
                        System.out.printf("action vblank=%d unit=%02x xy=%d,%d code=%02x arg=%02x%n", vblanks,
                            game.readByte(0xc669, 0), game.readByte(0xc66a, 0), game.readByte(0xc66b, 0),
                            game.readByte(0xc66c, 0), game.readByte(0xc66d, 0));
                    }
                }
            }
            if (!turbo && productionStart >= 0 && pc == 0x0dc9 && oldPC != 0x0dcd) {
                int sp = game.getRegisterValue(spIndex);
                int address = game.readByte(sp, 0) | (game.readByte(sp + 1, 0) << 8);
                String caller = String.format("%02x:%04x", address < 0x4000 ? 0 : game.getBank1(), address);
                activeCopy = copyCallers.computeIfAbsent(caller, ignored -> new long[2]);
                activeCopy[0]++;
            } else if (pc < 0x0dc9 || pc > 0x0dcf) {
                activeCopy = null;
            }
            int elapsed = CYCLES_PER_STEP - game.executeOnce(CYCLES_PER_STEP);
            if (elapsed <= 0) throw new IllegalStateException("Emulation stopped");
            if (productionStart >= 0 && (pc < 0x4000 || bank == 2)) {
                productionPCs[pc + (bank == 2 ? 0x8000 : 0)] += elapsed;
            }
            if (activeCopy != null) activeCopy[1] += elapsed;
            oldPC = pc;
            oldBank = bank;
            now += elapsed;
            if (turnStart < 0 && now > 1000L * frames) break;
        }
        throw new IllegalStateException("CPU turn not completed");
    }
}
