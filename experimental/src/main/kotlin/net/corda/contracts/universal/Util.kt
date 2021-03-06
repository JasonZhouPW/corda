package net.corda.contracts.universal

import com.google.common.collect.ImmutableSet
import com.google.common.collect.Sets
import net.corda.core.contracts.Frequency
import net.corda.core.crypto.CompositeKey
import net.corda.core.crypto.Party
import java.time.Instant
import java.time.LocalDate

fun Instant.toLocalDate(): LocalDate = LocalDate.ofEpochDay(this.epochSecond / 60 / 60 / 24)

fun LocalDate.toInstant(): Instant = Instant.ofEpochSecond(this.toEpochDay() * 60 * 60 * 24)

private fun liablePartiesVisitor(arrangement: Arrangement): ImmutableSet<CompositeKey> =
        when (arrangement) {
            is Zero -> ImmutableSet.of<CompositeKey>()
            is Obligation -> ImmutableSet.of(arrangement.from.owningKey)
            is And ->
                arrangement.arrangements.fold(ImmutableSet.builder<CompositeKey>(), { builder, k -> builder.addAll(liablePartiesVisitor(k)) }).build()
            is Actions ->
                arrangement.actions.fold(ImmutableSet.builder<CompositeKey>(), { builder, k -> builder.addAll(liablePartiesVisitor(k)) }).build()
            is RollOut -> liablePartiesVisitor(arrangement.template)
            is Continuation -> ImmutableSet.of<CompositeKey>()
            else -> throw IllegalArgumentException("liableParties " + arrangement)
        }

private fun liablePartiesVisitor(action: Action): ImmutableSet<CompositeKey> =
        if (action.actors.size != 1)
            liablePartiesVisitor(action.arrangement)
        else
            Sets.difference(liablePartiesVisitor(action.arrangement), ImmutableSet.of(action.actors.single())).immutableCopy()

/** Returns list of potentially liable parties for a given contract */
fun liableParties(contract: Arrangement): Set<CompositeKey> = liablePartiesVisitor(contract)

private fun involvedPartiesVisitor(action: Action): Set<CompositeKey> =
        Sets.union(involvedPartiesVisitor(action.arrangement), action.actors.map { it.owningKey }.toSet()).immutableCopy()

private fun involvedPartiesVisitor(arrangement: Arrangement): ImmutableSet<CompositeKey> =
        when (arrangement) {
            is Zero -> ImmutableSet.of<CompositeKey>()
            is Obligation -> ImmutableSet.of(arrangement.from.owningKey)
            is And ->
                arrangement.arrangements.fold(ImmutableSet.builder<CompositeKey>(), { builder, k -> builder.addAll(involvedPartiesVisitor(k)) }).build()
            is Actions ->
                arrangement.actions.fold(ImmutableSet.builder<CompositeKey>(), { builder, k -> builder.addAll(involvedPartiesVisitor(k)) }).build()
            else -> throw IllegalArgumentException()
        }

/** returns list of involved parties for a given contract */
fun involvedParties(arrangement: Arrangement): Set<CompositeKey> = involvedPartiesVisitor(arrangement)

fun replaceParty(action: Action, from: Party, to: Party): Action =
        if (action.actors.contains(from)) {
            Action(action.name, action.condition, action.actors - from + to, replaceParty(action.arrangement, from, to))
        } else
            Action(action.name, action.condition, action.actors, replaceParty(action.arrangement, from, to))

fun replaceParty(arrangement: Arrangement, from: Party, to: Party): Arrangement = when (arrangement) {
    is Zero -> arrangement
    is Obligation -> Obligation(arrangement.amount, arrangement.currency,
            if (arrangement.from == from) to else arrangement.from,
            if (arrangement.to == from) to else arrangement.to)
    is And -> And(arrangement.arrangements.map { replaceParty(it, from, to) }.toSet())
    is Actions -> Actions(arrangement.actions.map { replaceParty(it, from, to) }.toSet())
    else -> throw IllegalArgumentException()
}

fun extractRemainder(arrangement: Arrangement, action: Action): Arrangement = when (arrangement) {
    is Actions -> if (arrangement.actions.contains(action)) zero else arrangement
    is And -> {
        val a = arrangement.arrangements.map { extractRemainder(it, action) }.filter { it != zero }
        when (a.size) {
            0 -> zero
            1 -> a.single()
            else -> And(a.toSet())
        }
    }
    else -> arrangement
}

fun actions(arrangement: Arrangement): Map<String, Action> = when (arrangement) {
    is Zero -> mapOf()
    is Obligation -> mapOf()
    is Actions -> arrangement.actions.map { it.name to it }.toMap()
    is And -> arrangement.arrangements.map { actions(it) }.fold(mutableMapOf()) { m, x ->
        x.forEach { entry ->
            val (s, action) = entry
            m[s] = action
        }
        m
    }
    is RollOut -> mapOf()
    else -> throw IllegalArgumentException()
}

fun debugCompare(left: String, right: String) {
    assert(left == right)
}

fun <T> debugCompare(perLeft: Perceivable<T>, perRight: Perceivable<T>) {
    if (perLeft == perRight) return

    when (perLeft) {
        is UnaryPlus -> {
            if (perRight is UnaryPlus) {
                debugCompare(perLeft.arg, perRight.arg)
                return
            }
        }
        is PerceivableOperation -> {
            if (perRight is PerceivableOperation) {
                debugCompare(perLeft.left, perRight.left)
                debugCompare(perLeft.right, perRight.right)
                assert(perLeft.op == perRight.op)
                return
            }
        }
        is Interest -> {
            if (perRight is Interest) {
                debugCompare(perLeft.amount, perRight.amount)
                debugCompare(perLeft.interest, perRight.interest)
                debugCompare(perLeft.start, perRight.start)
                debugCompare(perLeft.end, perRight.end)
                assert(perLeft.dayCountConvention == perRight.dayCountConvention)
                return
            }
        }
        is Fixing -> {
            if (perRight is Fixing) {
                debugCompare(perLeft.date, perRight.date)
                debugCompare(perLeft.source, perRight.source)
                debugCompare(perLeft.date, perRight.date)
                return
            }
        }
    }

    assert(false)
}

fun debugCompare(parLeft: Party, parRight: Party) {
    assert(parLeft == parRight)
}

fun debugCompare(left: Frequency, right: Frequency) {
    assert(left == right)
}

fun debugCompare(left: LocalDate, right: LocalDate) {
    assert(left == right)
}

fun debugCompare(parLeft: Set<Party>, parRight: Set<Party>) {
    if (parLeft == parRight) return

    assert(parLeft == parRight)
}

fun debugCompare(arrLeft: Arrangement, arrRight: Arrangement) {
    if (arrLeft == arrRight) return

    when (arrLeft) {
        is Obligation -> {
            if (arrRight is Obligation) {

                debugCompare(arrLeft.amount, arrRight.amount)
                debugCompare(arrLeft.from, arrRight.from)
                debugCompare(arrLeft.to, arrRight.to)
                return
            }
        }
        is And -> {
            if (arrRight is And) {
                arrLeft.arrangements.zip(arrRight.arrangements).forEach {
                    debugCompare(it.first, it.second)
                }
                return
            }
        }
        is Actions -> {
            if (arrRight is Actions) {
                arrLeft.actions.zip(arrRight.actions).forEach {
                    debugCompare(it.first.arrangement, it.second.arrangement)
                    debugCompare(it.first.condition, it.second.condition)
                    debugCompare(it.first.actors, it.second.actors)
                    debugCompare(it.first.name, it.second.name)
                    return
                }
            }
        }
        is RollOut -> {
            if (arrRight is RollOut) {
                debugCompare(arrLeft.template, arrRight.template)
                debugCompare(arrLeft.startDate, arrRight.startDate)
                debugCompare(arrLeft.endDate, arrRight.endDate)
                debugCompare(arrLeft.frequency, arrRight.frequency)
                return
            }
        }
    }

    assert(false)
}
