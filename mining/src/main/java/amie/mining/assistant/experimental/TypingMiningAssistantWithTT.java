package amie.mining.assistant.experimental;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Set;

import javatools.datatypes.ByteString;
import javatools.datatypes.IntHashMap;
import javatools.datatypes.MultiMap;
import amie.data.KB;
import amie.data.TransitiveTypesKB;
import amie.data.U;
import amie.mining.assistant.*;
import amie.rules.Rule;

public class TypingMiningAssistantWithTT extends DefaultMiningAssistant {
	
	public static String topType = "owl:Thing";
	
	public static ByteString topTypeBS = ByteString.of(topType);

	public TypingMiningAssistantWithTT(KB dataSource) {
		super(dataSource);
		System.out.println("Materializing taxonomy...");
		amie.data.Schema.materializeTaxonomy(dataSource);
		this.exploitMaxLengthOption = false;
	}
	
	public Rule getInitialRule() {
		Rule query = new Rule();
		ByteString[] newEdge = query.fullyUnboundTriplePattern();
		ByteString[] succedent = newEdge.clone();
		succedent[1] = KB.TRANSITIVETYPEbs;
		succedent[2] = topTypeBS;
		Rule candidate = new Rule(succedent, (double)kb.countOneVariable(succedent));
		candidate.setId(1);
		candidate.setFunctionalVariablePosition(0);
		registerHeadRelation(candidate);
		return candidate;
	}
	
	@Override
	public Collection<Rule> getInitialAtoms(double minSupportThreshold) {
		return Arrays.asList(getInitialRule());
	}
	
	@Override
	public Collection<Rule> getInitialAtomsFromSeeds(Collection<ByteString> relations, 
			double minSupportThreshold) {
		return Arrays.asList(getInitialRule());
	}
	
	@Override
	public boolean isNotTooLong(Rule rule) {
		return rule.getRealLength() <= rule.getId();
	}
	
	@Override
	public boolean shouldBeOutput(Rule candidate) {
		return true;
	}
	
	@Override
	public boolean shouldBeClosed() {
		return false;
	}
	
	public void getSubTypingRules(Rule rule, double minSupportThreshold, Collection<Rule> output) {
		if (!rule.getHeadRelationBS().equals(KB.TRANSITIVETYPEbs)) {
			return;
		}
		if (rule.getBody().isEmpty()) {
			return;
		}
		ByteString[] head = rule.getHead();
		List<ByteString[]> body = rule.getBody();
		Set<ByteString> subTypes = amie.data.Schema.getSubTypes(kb, head[2]);
		int parentTypePos = Rule.firstIndexOfRelation(body, KB.TRANSITIVETYPEbs);
		for (ByteString subType : subTypes) {
			Rule succedent = new Rule(rule, rule.getSupport());
			if (parentTypePos == -1) {
				succedent = succedent.addAtom(KB.triple(head[0], KB.TRANSITIVETYPEbs, head[2]), 0);
			} else {
				succedent.getBody().get(parentTypePos)[2] = head[2];
			}
			succedent.getHead()[2] = subType;
			double cardinality = (double)kb.countDistinct(head[0], succedent.getTriples());
			if (cardinality >= minSupportThreshold) {
				succedent.setSupport(cardinality);
				succedent.setSupportRatio((double)cardinality / (double)this.kb.size());
				succedent.setId(rule.getId()+1);
				output.add(succedent);
			}
		}
	}
	
	public void getDomainRangeRules(Rule rule, double minSupportThreshold, Collection<Rule> output) {
		if (!rule.getHeadRelationBS().equals(KB.TRANSITIVETYPEbs)) {
			return;
		}
		List<ByteString> openVariables = rule.getOpenVariables();
		if (openVariables.isEmpty()) {
			return;
		}
		if (Rule.firstIndexOfRelation(rule.getBody(), KB.TRANSITIVETYPEbs) == -1) {
			return;
		}
		int cardinality;
		for (ByteString openVariable : openVariables) {
			ByteString[] newEdge = rule.fullyUnboundTriplePattern();
			newEdge[0] = openVariable;
			newEdge[1] = KB.TRANSITIVETYPEbs;
			
			Rule pattern = rule.addAtom(newEdge, 0);
			IntHashMap<ByteString> promisingTypes = kb.frequentBindingsOf(newEdge[2], pattern.getFunctionalVariable(), pattern.getTriples());
			for (ByteString promisingType : promisingTypes) {
				cardinality = promisingTypes.get(promisingType);
				if (cardinality >= minSupportThreshold) {
					newEdge[2] = promisingType;
					Rule candidate = rule.addAtom(newEdge, cardinality);
					candidate.addParent(rule);
					output.add(candidate);
				}
			}
		}
	}
	
	@Override
	public boolean testConfidenceThresholds(Rule candidate) {
		
		if(candidate.containsLevel2RedundantSubgraphs()) {
			return false;
		}	
		
		if(candidate.getStdConfidence() >= minStdConfidence){
			//Now check the confidence with respect to its ancestors
			List<Rule> ancestors = candidate.getAncestors();			
			for(int i = 0; i < ancestors.size(); ++i){
				double ancestorConfidence = ancestors.get(i).getStdConfidence();
				// Skyline technique on PCA confidence					
				if ((ancestors.get(i).getLength() > 1) && 
					  (ancestorConfidence >= candidate.getStdConfidence())) {
					return false;
				}
			}
		}else{
			return false;
		}
		
		return true;
	}
	
	@Override
	public void applyMiningOperators(Rule rule, double minSupportThreshold,
			Collection<Rule> danglingOutput, Collection<Rule> output) {
		System.err.println("Candidate: " + rule.getRuleString());
		// System.err.println("refined ?");
		if (!rule.isPerfect()) {
		//	System.err.println("yes");
			super.applyMiningOperators(rule, minSupportThreshold, danglingOutput, output);
		}
		getSubTypingRules(rule, minSupportThreshold, output);
		//getDomainRangeRules(rule, minSupportThreshold, output);
	}

	public static void main(String[] args) {
		KB kb = new KB();
		List<File> files = new ArrayList<>();
		for (String arg : args) {
			files.add(new File(arg));
		}
		try {
			kb.load(files);
		} catch (IOException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		}
		TypingMiningAssistantWithTT assistant = new TypingMiningAssistantWithTT(kb);
		Rule newRule;
		/*Rule initialRule = assistant.getInitialRule();
		System.out.println("Initial rule: " + initialRule.getRuleString());
		List<Rule> output = new ArrayList<>();
		System.out.println("SubTyping rules:");
		assistant.getSubTypingRules(initialRule, -1, output);
		for (Rule rule : output) {
			System.out.println(rule.getRuleString() + "\t" + String.valueOf(rule.getStdConfidence()));
		}
		output.clear();
		assistant.getDanglingAtoms(initialRule, -1, output);
		assert(!output.isEmpty());
		newRule = output.get(0);
		System.out.println("New rule: " + newRule.getRuleString());
		output.clear();
		System.out.println("SubTyping rules:");
		assistant.getSubTypingRules(newRule, -1, output);
		for (Rule rule : output) {
			System.out.println(rule.getRuleString() + "\t" + String.valueOf(rule.getStdConfidence()));
		}
		assert(!output.isEmpty());
		newRule = output.get(0);
		System.out.println("New rule: " + newRule.getRuleString());
		output.clear();
		System.out.println("SubTyping rules:");
		assistant.getSubTypingRules(newRule, -1, output);
		for (Rule rule : output) {
			System.out.println(rule.getRuleString() + "\t" + String.valueOf(rule.getStdConfidence()));
		}*/
		newRule = new Rule(KB.triple(ByteString.of("?x"), KB.TRANSITIVETYPEbs, ByteString.of("<wordnet_abstraction_100002137>")),
							KB.triples(KB.triple(ByteString.of("?x"), ByteString.of("<isMarriedTo>"), ByteString.of("?y")),
									   KB.triple(ByteString.of("?x"), KB.TRANSITIVETYPEbs, topTypeBS)), 0);
		System.out.println("New rule: " + newRule.getRuleString());
		long support = kb.countDistinct(ByteString.of("?x"), newRule.getTriples());
		System.out.println("Support: " + String.valueOf(support));
		System.out.println("MRT calls: " + String.valueOf(KB.STAT_NUMBER_OF_CALL_TO_MRT.get()));
		/*
		output.clear();
		System.out.println("SubTyping rules:");
		assistant.getSubTypingRules(newRule, -1, output);
		for (Rule rule : output) {
			System.out.println(rule.getRuleString() + "\t" + String.valueOf(rule.getStdConfidence()));
		}
		System.out.println("New rule: " + newRule.getRuleString());
		output.clear();
		System.out.println("DomainRange rules:");
		assistant.getDomainRangeRules(newRule, -1, output);
		for (Rule rule : output) {
			System.out.println(rule.getRuleString() + "\t" + String.valueOf(rule.getStdConfidence()));
		}*/
	}	
}