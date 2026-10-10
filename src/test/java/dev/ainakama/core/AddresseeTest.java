package dev.ainakama.core;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;

class AddresseeTest {
	private static final List<String> NAMES = List.of("ミナ", "Taro");
	private static final Set<String> QUICK = Set.of("ついてきて", "止まって");

	private static Optional<Addressee.Target> parse(String text, boolean nameOptional) {
		return Addressee.parse(text, NAMES, nameOptional, QUICK::contains);
	}

	private static Optional<Addressee.Target> named(String name, String message) {
		return Optional.of(new Addressee.Target(Addressee.Kind.NAMED, name, message));
	}

	private static Optional<Addressee.Target> everyone(String message) {
		return Optional.of(new Addressee.Target(Addressee.Kind.EVERYONE, "", message));
	}

	private static Optional<Addressee.Target> unnamed(String message) {
		return Optional.of(new Addressee.Target(Addressee.Kind.UNNAMED, "", message));
	}

	@Test
	void readsTheNameAtTheStart() {
		assertEquals(named("ミナ", "小屋を建てて"), parse("ミナ、小屋を建てて", false));
		assertEquals(named("ミナ", "小屋を建てて"), parse("ミナ小屋を建てて", false));
		assertEquals(named("ミナ", "小屋を建てて"), parse("@ミナ 小屋を建てて", false));
		assertEquals(named("Taro", "follow me"), parse("  taro: follow me ", false));
	}

	@Test
	void readsEveryone() {
		assertEquals(everyone("ついてきて"), parse("みんな、ついてきて", false));
		assertEquals(everyone("ついてきて"), parse("皆ついてきて", false));
		assertEquals(everyone("行こう"), parse("@全員 行こう", false));
	}

	@Test
	void ordinaryTalkStartingWithEveryoneIsNotForEveryone() {
		// 区切りがなく、決まった言い方でもないので、全員への呼びかけではない
		assertEquals(Optional.empty(), parse("皆で行こう", false));
		assertEquals(unnamed("皆で行こう"), parse("皆で行こう", true));
	}

	@Test
	void withoutNameIsIgnoredWhenNameIsRequired() {
		assertEquals(Optional.empty(), parse("ついてきて", false));
		assertEquals(Optional.empty(), parse("石を掘ってきて", false));
	}

	@Test
	void withoutNameIsDeliveredWhenNameIsOptional() {
		assertEquals(unnamed("ついてきて"), parse("ついてきて", true));
		assertEquals(unnamed("石を掘ってきて"), parse(" 石を掘ってきて ", true));
		assertEquals(unnamed("石を掘ってきて"), parse("@石を掘ってきて", true));
	}

	@Test
	void nameStillChoosesWhoWhenNameIsOptional() {
		assertEquals(named("Taro", "止まって"), parse("Taro、止まって", true));
		assertEquals(everyone("止まって"), parse("みんな止まって", true));
	}

	@Test
	void longerNameWinsWhenOneNameStartsWithAnother() {
		List<String> names = List.of("ミナ", "ミナト");
		assertEquals(named("ミナト", "ついてきて"), Addressee.parse("ミナト、ついてきて", names, true, QUICK::contains));
		assertEquals(named("ミナ", "ついてきて"), Addressee.parse("ミナ、ついてきて", names, true, QUICK::contains));
	}

	@Test
	void nameAloneOrNothingIsNotAMessage() {
		assertEquals(Optional.empty(), parse("ミナ", true));
		assertEquals(Optional.empty(), parse("ミナ、", true));
		assertEquals(Optional.empty(), parse("みんな！", true));
		assertEquals(Optional.empty(), parse("   ", true));
		assertEquals(Optional.empty(), parse("@", true));
	}
}
