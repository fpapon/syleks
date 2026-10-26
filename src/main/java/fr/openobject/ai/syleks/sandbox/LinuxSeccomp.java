/*
 * Copyright (c) 2026 - present - Francois Papon - francois.papon@openobject.fr
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *  http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package fr.openobject.ai.syleks.sandbox;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.List;

/** Native x86-64 Linux BPF. Reject other ABIs, including x32 and compat socketcall. */
final class LinuxSeccomp {
    private record Instruction(int code, int yes, int no, int value) {}

    static byte[] filter(boolean network) {
        var code = new ArrayList<Instruction>();
        code.add(new Instruction(0x20, 0, 0, 4)); // seccomp_data.arch
        code.add(new Instruction(0x15, 1, 0, 0xc000003e)); // AUDIT_ARCH_X86_64
        code.add(new Instruction(0x06, 0, 0, 0x80000000)); // kill other ABIs
        code.add(new Instruction(0x20, 0, 0, 0)); // syscall number
        code.add(new Instruction(0x35, 0, 1, 0x40000000)); // x32 syscall bit
        code.add(new Instruction(0x06, 0, 0, 0x80000000));
        // Namespace mutation, privilege/kernel entry points, ptrace, keys and io_uring socket
        // bypasses.
        for (int syscall :
                List.of(
                        53, 101, 155, 165, 166, 167, 168, 169, 175, 176, 246, 248, 249, 250, 272,
                        298, 303, 304, 308, 313, 321, 323, 425, 426, 427, 428, 429, 430, 431, 432,
                        433, 442)) {
            code.add(new Instruction(0x15, 0, 1, syscall));
            code.add(new Instruction(0x06, 0, 0, 0x00050001)); // EPERM
        }
        if (network) {
            code.add(new Instruction(0x15, 0, 4, 41)); // socket()
            code.add(new Instruction(0x20, 0, 0, 16)); // domain argument low word
            code.add(new Instruction(0x15, 2, 0, 2)); // AF_INET
            code.add(new Instruction(0x15, 1, 0, 10)); // AF_INET6
            code.add(new Instruction(0x06, 0, 0, 0x00050001));
        } else {
            code.add(new Instruction(0x15, 0, 1, 41));
            code.add(new Instruction(0x06, 0, 0, 0x00050001));
        }
        code.add(new Instruction(0x06, 0, 0, 0x7fff0000)); // allow
        var bytes = ByteBuffer.allocate(code.size() * 8).order(ByteOrder.LITTLE_ENDIAN);
        for (var instruction : code)
            bytes.putShort((short) instruction.code())
                    .put((byte) instruction.yes())
                    .put((byte) instruction.no())
                    .putInt(instruction.value());
        return bytes.array();
    }
}
