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
package fr.openobject.ai.syleks.app;

import static fr.openobject.ai.syleks.app.PermissionPolicy.Decision.*;
import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class ShellCommandPolicyTest {
    private PermissionPolicy policy(
            PermissionPolicy.Decision base, PermissionPolicy.Rule... rules) {
        var entries = new java.util.ArrayList<PermissionPolicy.Rule>();
        entries.add(new PermissionPolicy.Rule("bash", "*", base));
        entries.addAll(List.of(rules));
        return new PermissionPolicy(entries);
    }

    private PermissionPolicy restricted() {
        return policy(
                DENY,
                new PermissionPolicy.Rule("bash", "printf *", ALLOW),
                new PermissionPolicy.Rule("bash", "touch *", DENY));
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "printf ok; touch marker", "printf ok && touch marker", "printf ok || touch marker",
                "printf ok | touch marker", "printf ok & touch marker", "printf ok\ntouch marker",
                "touch marker; printf ok", "printf ok; printf ok; touch marker"
            })
    void deniesTheWholeListIfAnyActionIsDenied(String command) {
        assertEquals(DENY, restricted().evaluate("bash", command));
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "printf ok; 'touch' marker", "printf ok; t'o'uch marker",
                "printf ok; \"touch\" marker", "printf ok; \\touch marker",
                "printf ok; to\\\nuch marker", "printf ok;\ttouch\tmarker"
            })
    void normalizesLiteralQuotingEscapingAndWhitespaceBeforeMatching(String command) {
        assertEquals(DENY, restricted().evaluate("bash", command));
    }

    @Test
    void normalizesQuotedArgumentsSoTheyCannotEscapeDenyPatterns() {
        var policy = policy(ALLOW, new PermissionPolicy.Rule("bash", "printf blocked*", DENY));
        assertEquals(DENY, policy.evaluate("bash", "printf 'blocked'"));
        assertEquals(DENY, policy.evaluate("bash", "printf b'l'o'c'ked"));
        assertEquals(ALLOW, policy.evaluate("bash", "printf safe"));
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "printf '$(touch marker)'",
                "printf '; touch marker'",
                "printf \"; touch marker\"",
                "printf \\;",
                "printf \\$literal",
                "printf \"\\$literal\"",
                "printf '%s\\n' hello"
            })
    void quotedOperatorsAreNotExecutableCommands(String command) {
        assertEquals(ALLOW, restricted().evaluate("bash", command));
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "printf $(touch marker)",
                "printf \"$(touch marker)\"",
                "printf `touch marker`",
                "printf <(touch marker)",
                "printf >(touch marker)",
                "printf ${command}",
                "printf $command",
                "printf $((1+1))",
                "printf $'hello'",
                "printf *.txt",
                "printf {one,two}",
                "printf ~",
                "printf ok # comment\ntouch marker",
                "printf ok; (touch marker)",
                "printf ok; { touch marker; }",
                "printf ok; if true; then touch marker; fi",
                "X=1 printf ok",
                "printf ok; eval 'touch marker'",
                "printf ok; command touch marker",
                "printf ok; /bin/bash -c 'touch marker'",
                "printf ok; env touch marker",
                "printf ok; source script",
                "printf ok; xargs touch",
                "alias hidden='touch marker'; hidden",
                "trap 'touch marker' EXIT",
                "hash -p /usr/bin/touch harmless; harmless marker",
                "export BASH_ENV=script",
                "shopt -s expand_aliases",
                "enable -f module.so printf",
                "printf -v PATH directory",
                "printf '%n' PATH",
                "'printf hidden' marker"
            })
    void failsClosedOnDynamicOrIndirectSyntaxUnderGranularPermissions(String command) {
        var policy = policy(ALLOW, new PermissionPolicy.Rule("bash", "touch *", DENY));
        assertEquals(DENY, policy.evaluate("bash", command));
        assertTrue(
                ShellCommandPolicy.assess(policy, command).reason().contains("cannot be checked"));
    }

    @Test
    void checksEachRedirectionAsASeparateAction() {
        assertEquals(DENY, restricted().evaluate("bash", "printf ok > marker"));
        var policy =
                restricted()
                        .overlay(
                                new PermissionPolicy(
                                        List.of(
                                                new PermissionPolicy.Rule(
                                                        "bash", "> marker", ALLOW))));
        assertEquals(ALLOW, policy.evaluate("bash", "printf ok > marker"));
        assertEquals(DENY, policy.evaluate("bash", "printf ok > marker; touch other"));
        assertEquals(DENY, policy.evaluate("bash", "printf ok > other"));
        assertEquals(DENY, policy.evaluate("bash", "printf ok >> marker"));
        assertEquals(DENY, policy.evaluate("bash", "printf ok < marker"));
    }

    @Test
    void distinguishesLiteralDescriptorNumbersFromQuotedArguments() {
        var policy = policy(ALLOW, new PermissionPolicy.Rule("bash", "> *", DENY));
        assertEquals(ALLOW, policy.evaluate("bash", "printf ok 2> marker"));
        assertEquals(DENY, policy.evaluate("bash", "printf ok '2'> marker"));
        assertEquals(DENY, policy.evaluate("bash", "printf ok \\2> marker"));
        assertEquals(DENY, policy.evaluate("bash", "printf ok 2 > marker"));
        var descriptor = policy(ALLOW, new PermissionPolicy.Rule("bash", "2> *", DENY));
        assertEquals(DENY, descriptor.evaluate("bash", "printf ok 2\\\n> marker"));
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "printf ok; printf more",
                "printf ok | printf more",
                "printf ok && printf more",
                "printf ok |\n printf more",
                "\n\n printf ok &&\n\n printf more\n"
            })
    void allowsListsWhoseActionsAreAllAllowed(String command) {
        assertEquals(ALLOW, restricted().evaluate("bash", command));
    }

    @Test
    void propagatesAskAndNeverRelaxesDeny() {
        var policy =
                policy(
                        ALLOW,
                        new PermissionPolicy.Rule("bash", "printf review", ASK),
                        new PermissionPolicy.Rule("bash", "touch *", DENY));
        assertEquals(ASK, policy.evaluate("bash", "printf ok; printf review"));
        assertEquals(DENY, policy.evaluate("bash", "printf review; touch marker"));
    }

    @Test
    void retainsExplicitRestrictionsOnTheWholeScript() {
        String command = "printf first; printf second";
        var deny = policy(ALLOW, new PermissionPolicy.Rule("bash", command, DENY));
        assertEquals(DENY, deny.evaluate("bash", command));
        var ask = policy(ALLOW, new PermissionPolicy.Rule("bash", "*;*", ASK));
        assertEquals(ASK, ask.evaluate("bash", command));
        assertEquals(
                DENY,
                ask.overlay(
                                new PermissionPolicy(
                                        List.of(
                                                new PermissionPolicy.Rule(
                                                        "bash", "printf second", DENY))))
                        .evaluate("bash", command));
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "printf ok &&",
                "printf ok |",
                "printf 'unfinished",
                "printf ok >",
                "printf ok <<EOF\nbody\nEOF",
                "printf ok <<< text"
            })
    void refusesIncompleteCommandsAndHereDocuments(String command) {
        assertEquals(DENY, restricted().evaluate("bash", command));
    }

    @Test
    void refusesInvalidRedirectionOperatorsEvenUnderAnOtherwisePermissiveGranularPolicy() {
        var policy = policy(ALLOW, new PermissionPolicy.Rule("bash", "touch *", DENY));
        assertEquals(DENY, policy.evaluate("bash", "printf ok <| input"));
    }

    @Test
    void retainsWholeShellAuthorityOnlyWhenTheEffectivePolicyIsUniform() {
        String script = "printf '%s' \"$(printf dynamic)\" > marker";
        assertEquals(ALLOW, policy(ALLOW).evaluate("bash", script));
        assertEquals(ASK, policy(ASK).evaluate("bash", script));
        assertEquals(DENY, policy(DENY).evaluate("bash", script));
        var overlaid = restricted().overlay(policy(ALLOW));
        assertEquals(ALLOW, overlaid.evaluate("bash", script));
        assertEquals(
                DENY,
                overlaid.overlay(
                                new PermissionPolicy(
                                        List.of(
                                                new PermissionPolicy.Rule(
                                                        "bash", "touch *", DENY))))
                        .evaluate("bash", script));
    }
}
