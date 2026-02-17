/**
 * Agent loop implementation — the ReAct (Reason + Act) execution engine.
 *
 * <p>Coordinates LLM calls with tool execution in an iterative loop:
 * the model reasons about the task, invokes tools, observes results,
 * and repeats until it has a final answer.
 */
package dev.chelava.core.agent;
