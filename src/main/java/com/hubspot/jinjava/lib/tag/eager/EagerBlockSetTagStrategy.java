package com.hubspot.jinjava.lib.tag.eager;

import com.google.common.annotations.Beta;
import com.hubspot.jinjava.interpret.Context.TemporaryValueClosable;
import com.hubspot.jinjava.interpret.DeferredValueException;
import com.hubspot.jinjava.interpret.JinjavaInterpreter;
import com.hubspot.jinjava.lib.tag.RawTag;
import com.hubspot.jinjava.lib.tag.SetTag;
import com.hubspot.jinjava.tree.TagNode;
import com.hubspot.jinjava.tree.parse.TagToken;
import com.hubspot.jinjava.util.EagerContextWatcher;
import com.hubspot.jinjava.util.EagerExpressionResolver.EagerExpressionResult;
import com.hubspot.jinjava.util.EagerExpressionResolver.EagerExpressionResult.ResolutionState;
import com.hubspot.jinjava.util.EagerReconstructionUtils;
import com.hubspot.jinjava.util.LengthLimitingStringJoiner;
import com.hubspot.jinjava.util.PrefixToPreserveState;
import java.util.Optional;
import java.util.stream.Stream;
import org.apache.commons.lang3.tuple.Triple;

@Beta
public class EagerBlockSetTagStrategy extends EagerSetTagStrategy {

  public static final EagerBlockSetTagStrategy INSTANCE = new EagerBlockSetTagStrategy(
    new SetTag()
  );

  protected EagerBlockSetTagStrategy(SetTag setTag) {
    super(setTag);
  }

  @Override
  protected EagerExecutionResult getEagerExecutionResult(
    TagNode tagNode,
    String[] variables,
    String expression,
    JinjavaInterpreter interpreter
  ) {
    EagerExecutionResult eagerExecutionResult = EagerContextWatcher.executeInChildContext(
      eagerInterpreter ->
        EagerExpressionResult.fromSupplier(
          () -> SetTag.renderChildren(tagNode, eagerInterpreter, variables[0]),
          eagerInterpreter
        ),
      interpreter,
      EagerContextWatcher.EagerChildContextConfig
        .newBuilder()
        .withTakeNewValue(true)
        .build()
    );
    if (
      (!eagerExecutionResult.getResult().isFullyResolved() &&
        !eagerExecutionResult.getSpeculativeBindings().isEmpty()) ||
      interpreter.getContext().isDeferredExecutionMode()
    ) {
      EagerReconstructionUtils.resetAndDeferSpeculativeBindings(
        interpreter,
        eagerExecutionResult
      );
    }
    eagerExecutionResult =
      unwrapRawTagsIfFullyResolved(interpreter, eagerExecutionResult);
    return eagerExecutionResult;
  }

  private static EagerExecutionResult unwrapRawTagsIfFullyResolved(
    JinjavaInterpreter interpreter,
    EagerExecutionResult eagerExecutionResult
  ) {
    if (
      eagerExecutionResult.getResult().isFullyResolved() &&
      eagerExecutionResult
        .getResult()
        .toString(true)
        .contains(
          interpreter.getConfig().getTokenScannerSymbols().getExpressionStartWithTag() +
          " " +
          RawTag.TAG_NAME
        )
    ) {
      try (
        TemporaryValueClosable<Boolean> temporaryValueClosable = interpreter
          .getContext()
          .withUnwrapRawOverride()
      ) {
        eagerExecutionResult =
          new EagerExecutionResult(
            EagerExpressionResult.fromString(
              interpreter.renderFlat(eagerExecutionResult.asTemplateString()),
              ResolutionState.FULL
            ),
            eagerExecutionResult.getSpeculativeBindings()
          );
      }
    }
    return eagerExecutionResult;
  }

  @Override
  protected Optional<String> resolveSet(
    TagNode tagNode,
    String[] variables,
    EagerExecutionResult eagerExecutionResult,
    JinjavaInterpreter interpreter
  ) {
    int filterPos = tagNode.getHelpers().indexOf('|');
    try {
      setTag.executeSet(
        (TagToken) tagNode.getMaster(),
        interpreter,
        variables,
        eagerExecutionResult.getResult().toList(),
        true
      );
      if (filterPos >= 0) {
        EagerExecutionResult filterResult =
          EagerInlineSetTagStrategy.INSTANCE.getEagerExecutionResult(
            tagNode,
            variables,
            tagNode.getHelpers().trim(),
            interpreter
          );
        if (filterResult.getResult().isFullyResolved()) {
          setTag.executeSet(
            (TagToken) tagNode.getMaster(),
            interpreter,
            variables,
            filterResult.getResult().toList(),
            true
          );
        } else {
          // We could evaluate the block part, and just need to defer the filtering.
          return Optional.of(
            runInlineStrategy(tagNode, variables, filterResult, interpreter)
          );
        }
      }
      return Optional.of("");
    } catch (DeferredValueException ignored) {}
    return Optional.empty();
  }

  @Override
  protected Triple<String, String, String> getPrefixTokenAndSuffix(
    TagNode tagNode,
    String[] variables,
    EagerExecutionResult eagerExecutionResult,
    JinjavaInterpreter interpreter
  ) {
    LengthLimitingStringJoiner joiner = new LengthLimitingStringJoiner(
      interpreter.getConfig().getMaxOutputSize(),
      " "
    )
      .add(tagNode.getSymbols().getExpressionStartWithTag())
      .add(tagNode.getTag().getName())
      .add(variables[0])
      .add(tagNode.getSymbols().getExpressionEndWithTag());

    PrefixToPreserveState prefixToPreserveState = getPrefixToPreserveState(
      eagerExecutionResult,
      variables,
      interpreter
    )
      .withAllInFront(
        EagerReconstructionUtils.handleDeferredTokenAndReconstructReferences(
          interpreter,
          DeferredToken
            .builderFromImage(joiner.toString(), tagNode.getMaster())
            .addSetDeferredWords(Stream.of(variables))
            .build()
        )
      );
    String suffixToPreserveState = getSuffixToPreserveState(variables, interpreter);
    return Triple.of(
      prefixToPreserveState.toString(),
      joiner.toString(),
      suffixToPreserveState
    );
  }

  @Override
  protected void attemptResolve(
    TagNode tagNode,
    String[] variables,
    EagerExecutionResult eagerExecutionResult,
    JinjavaInterpreter interpreter
  ) {
    try {
      // try to override the value for just this context
      setTag.executeSet(
        (TagToken) tagNode.getMaster(),
        interpreter,
        variables,
        eagerExecutionResult.getResult().toList(),
        true
      );
    } catch (DeferredValueException ignored) {}
  }

  @Override
  protected String buildImage(
    TagNode tagNode,
    String[] variables,
    EagerExecutionResult eagerExecutionResult,
    Triple<String, String, String> triple,
    JinjavaInterpreter interpreter
  ) {
    int filterPos = tagNode.getHelpers().indexOf('|');
    String filterSetPostfix = "";
    if (filterPos >= 0) {
      EagerExecutionResult filterResult =
        EagerInlineSetTagStrategy.INSTANCE.getEagerExecutionResult(
          tagNode,
          variables,
          tagNode.getHelpers().trim(),
          interpreter
        );
      if (filterResult.getResult().isFullyResolved()) {
        setTag.executeSet(
          (TagToken) tagNode.getMaster(),
          interpreter,
          variables,
          filterResult.getResult().toList(),
          true
        );
      }
      filterSetPostfix = runInlineStrategy(tagNode, variables, filterResult, interpreter);
    }

    return EagerReconstructionUtils.wrapInAutoEscapeIfNeeded(
      triple.getLeft() +
      triple.getMiddle() +
      eagerExecutionResult.getResult().toString(true) +
      EagerReconstructionUtils.reconstructEnd(tagNode) +
      filterSetPostfix +
      triple.getRight(),
      interpreter
    );
  }

  private String runInlineStrategy(
    TagNode tagNode,
    String[] variables,
    EagerExecutionResult eagerExecutionResult,
    JinjavaInterpreter interpreter
  ) {
    Triple<String, String, String> triple =
      EagerInlineSetTagStrategy.INSTANCE.getPrefixTokenAndSuffix(
        tagNode,
        variables,
        eagerExecutionResult,
        interpreter
      );
    if (
      eagerExecutionResult.getResult().isFullyResolved() &&
      interpreter.getContext().isDeferredExecutionMode()
    ) {
      EagerInlineSetTagStrategy.INSTANCE.attemptResolve(
        tagNode,
        variables,
        eagerExecutionResult,
        interpreter
      );
    }
    return EagerInlineSetTagStrategy.INSTANCE.buildImage(
      tagNode,
      variables,
      eagerExecutionResult,
      triple,
      interpreter
    );
  }
}
