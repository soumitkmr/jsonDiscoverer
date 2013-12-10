/*******************************************************************************
 * Copyright (c) 2008, 2013
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 *
 * Contributors:
 *    Javier Canovas (javier.canovas@inria.fr) 
 *******************************************************************************/

package fr.inria.atlanmod.discoverer;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.eclipse.emf.common.util.URI;
import org.eclipse.emf.ecore.EAttribute;
import org.eclipse.emf.ecore.EClass;
import org.eclipse.emf.ecore.EClassifier;
import org.eclipse.emf.ecore.EPackage;
import org.eclipse.emf.ecore.EReference;
import org.eclipse.emf.ecore.EStructuralFeature;
import org.eclipse.emf.ecore.EcoreFactory;
import org.eclipse.emf.ecore.EcorePackage;
import org.eclipse.emf.ecore.resource.Resource;
import org.eclipse.emf.ecore.resource.ResourceSet;
import org.eclipse.emf.ecore.resource.impl.ResourceSetImpl;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import coverage.ConceptMapping;
import coverage.util.CoverageCreator;

/**
 * Performs a composition between metamodels obtained from JSON files
 * This implementation does not depend on Xtext
 * 
 * @author Javier Canovas (javier.canovas@inria.fr)
 *
 */
public class JsonComposer {
	final static double CLASS_MATCHING_THRESHOLD = 0.3;

	private JsonSourceSet sourceSet;
	
	private HashMap<String, EClass> registry;
	private List<CoverageCreator> coverageCreators;
	
	HashMap<EAttribute, List<Object>> cacheValues;
	
	private final static Logger LOGGER = Logger.getLogger(JsonComposer.class.getName());
	
	public JsonComposer(JsonSourceSet sourceSet) {
		if(sourceSet == null) 
			throw new IllegalArgumentException("SourceSet cannot be null");
		else if(sourceSet.getJsonSources().size() == 0) 
			throw new IllegalArgumentException("At least 1 source is required to compose");

		this.sourceSet = sourceSet;
		// All the sources must include the metamodel
		for(JsonSource jsonSource : this.sourceSet.getJsonSources()) {
			if(jsonSource.getMetamodel() == null) {
				// For those not including the metamodel, it is discovered
				JsonDiscoverer discoverer = new JsonDiscoverer();
				discoverer.discoverMetamodel(jsonSource);
			}
		}
		this.cacheValues = new HashMap<EAttribute, List<Object>>();
		
		LOGGER.setLevel(Level.OFF);
	}
		
	/**
	 * Saves the coverage information
	 * 
	 * @param resultPath The path where the converage information will be saved
	 */
	public void saveCoverage(List<File> resultPaths) {
		if(resultPaths.size() == coverageCreators.size())
			for(int i = 0; i < coverageCreators.size(); i++) {
				CoverageCreator coverageCreator = coverageCreators.get(i); 
				coverageCreator.save(resultPaths.get(i));
			}
		else {
			throw new IllegalArgumentException("The size of the paths must match the size of coverage files");
		}
	}
	
	/**
	 * Compose the JSON files
	 * 
	 * @param resultingName The name for the resulting package metamodel
	 * @param resultPath The path where the resulting metamodel will be stored
	 * @return The resulting metamodel as EPackage
	 * @throws FileNotFoundException
	 */
	public JsonSourceSet compose(File resultPath) throws FileNotFoundException {
		JsonSourceSet sourceSet = compose();
		saveEPackage(sourceSet.getMetamodel(), resultPath);
		return sourceSet;
	}
	
	/**
	 * Compose the JSON sources received when building the object
	 * 
	 * @return
	 * @throws FileNotFoundException
	 */
	public JsonSourceSet compose() throws FileNotFoundException  {
		// Creating the resulting metamodel
		EPackage finalPackage = EcoreFactory.eINSTANCE.createEPackage();
		finalPackage.setName(sourceSet.getName());
		finalPackage.setNsPrefix("composed" + sourceSet.getName().charAt(0));
		finalPackage.setNsURI("http://fr.inria.atlanmod/discovered/" + sourceSet.getName());
		LOGGER.finer("Package created");

		// Initializing variables
		registry = new HashMap<String, EClass>();
		List<EReference> referencesToCheck = new ArrayList<EReference>();
		coverageCreators = new ArrayList<CoverageCreator>();
		for(JsonSource jsonSource : this.sourceSet.getJsonSources()) {
			LOGGER.finer("Analizing JSON source: " + jsonSource.getName());
			CoverageCreator coverageCreator = new CoverageCreator(jsonSource.getName(), jsonSource.getMetamodel(), finalPackage);

			for(EClassifier classifier : jsonSource.getMetamodel().getEClassifiers()) {
				if (classifier instanceof EClass) {
					EClass eClass = (EClass) classifier;
					EClass registryElement = lookForSimilarEClass(eClass);
					if(registryElement == null) {
						LOGGER.finer("  " + eClass.getName() + " class being duplicated...");
						EClass duplicatedEClass = cloneEClass(eClass, coverageCreator);
						registry.put(eClass.getName(), duplicatedEClass);
						coverageCreator.createConceptMapping(eClass, duplicatedEClass);
						eClass = duplicatedEClass;
						LOGGER.finer("  " + eClass.getName() + " class duplicated and added");
					} else {
						LOGGER.finer("  " + eClass.getName() + " class being composed with " + registryElement.getName() + "...");
						coverageCreator.createConceptMapping(eClass, registryElement);
						composeAttributes(registryElement, eClass, coverageCreator);
						composeReferences(registryElement, eClass, coverageCreator);
						eClass = registryElement;
						LOGGER.finer("  " + eClass.getName() + " class composed with " + registryElement.getName());
					}
					for(EStructuralFeature otherFeature : eClass.getEStructuralFeatures()) 
						if (otherFeature instanceof EReference) 
							referencesToCheck.add((EReference) otherFeature);

				}
			}
			coverageCreators.add(coverageCreator);
		}

		for(EClass eClass : registry.values()) {
			finalPackage.getEClassifiers().add(eClass);
		}

		EClass unknown = EcoreFactory.eINSTANCE.createEClass();
		unknown.setName("Unknown");
		boolean unknownUsed = false;

		for(EReference reference : referencesToCheck) {
			EClassifier referredEClassifier = reference.getEType();
			if (referredEClassifier instanceof EClass) {
				EClass referredEClass = (EClass) referredEClassifier;
				EClass registryElement = registry.get(referredEClass.getName());
				if(registryElement == null) {
					ConceptMapping mapping = null;
					for(CoverageCreator coverageCreator : coverageCreators) {
						mapping = coverageCreator.getConceptMappingFromSource(referredEClass);
						if(mapping != null)
							break;
					}
					if(mapping != null) {
						EClass mappedClass = mapping.getTarget();
						reference.setEType(mappedClass);
						LOGGER.finer("Reference " + reference.getName() + " re-assigned to " + mappedClass.getName());
					} else {
						reference.setEType(unknown);
						LOGGER.finer("Reference " + reference.getName() + " with unknown type");
						unknownUsed = true;
					}
				} else {
					reference.setEType(registryElement);
					LOGGER.finer("Reference " + reference.getName() + " re-assigned");
				}
			}
		}

		if(unknownUsed) {
			finalPackage.getEClassifiers().add(unknown);
		}

		this.sourceSet.setMetamodel(finalPackage);
		return sourceSet;
	}

	private void composeAttributes(EClass existingClass, EClass otherClass, CoverageCreator coverageCreator) throws FileNotFoundException  {
		for(EStructuralFeature otherFeature : otherClass.getEStructuralFeatures()) {
			if (otherFeature instanceof EAttribute) {
				EAttribute otherAttribute = (EAttribute) otherFeature;
				EStructuralFeature existingFeature = existingClass.getEStructuralFeature(otherAttribute.getName());
				if(existingFeature == null) {
					EAttribute similarAttribute = lookForSimilarAttribute(existingClass, otherAttribute, coverageCreator);
					if(similarAttribute == null) {
						EAttribute newAttribute = duplicateAttribute(otherAttribute);
						existingClass.getEStructuralFeatures().add(newAttribute);
						LOGGER.finer("    " + "Attribute " + newAttribute.getName() + " added");
						existingFeature = newAttribute;
					} else {
						LOGGER.finer("    " + "Attribute similar found: " + similarAttribute.getName());
						existingFeature = similarAttribute;
					}
				} else {
					existingFeature.setEType(EcorePackage.Literals.ESTRING);
					LOGGER.finer("    " + "Attribute " + existingFeature.getName() + " refined to String");
				}				
				coverageCreator.createAttMapping(otherAttribute, (EAttribute) existingFeature);
			}
		}

	}

	private void composeReferences(EClass existingClass, EClass otherClass, CoverageCreator coverageCreator) {
		for(EStructuralFeature otherFeature : otherClass.getEStructuralFeatures()) {
			if (otherFeature instanceof EReference) {
				EReference otherReference = (EReference) otherFeature;
				EStructuralFeature existingFeature = existingClass.getEStructuralFeature(otherReference.getName());
				if(existingFeature == null) {
					EReference newReference = duplicateReference(otherReference);
					existingClass.getEStructuralFeatures().add(newReference);
					LOGGER.finer("    " + "Reference " + newReference.getName() + " added");
					existingFeature = newReference;
					coverageCreator.createRefMapping(otherReference, (EReference) existingFeature);
				} 			
			}
		}
	}

	private EAttribute duplicateAttribute(EAttribute otherAttribute) {
		EAttribute newAttribute = EcoreFactory.eINSTANCE.createEAttribute();
		newAttribute.setName(otherAttribute.getName());
		newAttribute.setEType(otherAttribute.getEType());
		newAttribute.setUpperBound(otherAttribute.getUpperBound());
		newAttribute.setLowerBound(otherAttribute.getLowerBound());
		return newAttribute;
	}

	private EReference duplicateReference(EReference otherReference) {
		EReference newReference = EcoreFactory.eINSTANCE.createEReference();
		newReference.setName(otherReference.getName());
		newReference.setEType(otherReference.getEType());
		newReference.setUpperBound(otherReference.getUpperBound());
		newReference.setLowerBound(otherReference.getLowerBound());
		return newReference;		
	}

	/**
	 * Clones a Class having into consideration its structura features.
	 * 
	 * @param otherClass
	 * @param coverageCreator
	 * @return
	 * @throws FileNotFoundException
	 */
	private EClass cloneEClass(EClass otherClass, CoverageCreator coverageCreator) throws FileNotFoundException  {
		EClass newClass = EcoreFactory.eINSTANCE.createEClass();
		newClass.setName(otherClass.getName());
		newClass.setAbstract(otherClass.isAbstract());

		for(EStructuralFeature otherFeature : otherClass.getEStructuralFeatures()) {
			if (otherFeature instanceof EReference) {
				LOGGER.finer("    " + "Duplicating " + otherFeature.getName() + " reference");
				EReference duplicatedReference = duplicateReference((EReference) otherFeature);
				newClass.getEStructuralFeatures().add(duplicatedReference);
				coverageCreator.createRefMapping((EReference) otherFeature, duplicatedReference);	
			} else if (otherFeature instanceof EAttribute) {
				LOGGER.finer("    " + "Duplicating " + otherFeature.getName() + " attribute");
				EAttribute duplicatedAttribute = duplicateAttribute((EAttribute) otherFeature);
				newClass.getEStructuralFeatures().add(duplicatedAttribute);
				coverageCreator.createAttMapping((EAttribute) otherFeature, duplicatedAttribute);
				cacheValues.put(duplicatedAttribute, getJSONValues(duplicatedAttribute, coverageCreator.getName()));
			}
		}
		return newClass;
	}

	private EClass lookForSimilarEClass(EClass existingClass) { 
		LOGGER.finer("  " + "Looking for classes similar to " + existingClass.getName());
		// If it is the input class, ignore it!
		if(existingClass.getName().endsWith("Input")) 
			return null;
		
		EClass registryElement = registry.get(existingClass.getName());
		if(registryElement != null) {
			LOGGER.finer("    " + "Found matching name");
			return registryElement;
		} else {
			for(EClass registeredClass : registry.values()) {
				double totalRegisteredFeatures = registeredClass.getEStructuralFeatures().size();
				double matchingFeatures = 0;
				for(EStructuralFeature registeredFeature : registeredClass.getEStructuralFeatures()) {
					for(EStructuralFeature existingFeature : existingClass.getEStructuralFeatures()) {
						LOGGER.finest("      " + "Comparing " + registeredFeature.getName() + " with " + existingFeature.getName());
						if(registeredFeature.getName().equals(existingFeature.getName())) {
							LOGGER.finest("        " + "Yes! Ratio now: " + matchingFeatures / totalRegisteredFeatures );
							matchingFeatures++;
							
							break;
						}
					}
				}
				double matchingRatio = matchingFeatures / totalRegisteredFeatures;
				if(matchingRatio > CLASS_MATCHING_THRESHOLD) {
					LOGGER.finer("    " + "Found similar class " + registeredClass.getName() + " with ratio " + matchingRatio);
					return registeredClass;
				} else {
					LOGGER.finest("    " + "Class " + registeredClass.getName() + " no similar with ratio " + matchingRatio);
				}
			}
		}
		LOGGER.finer("    " + "Not found");
		return null;		
	}

	private EAttribute lookForSimilarAttribute(EClass existingClass, EAttribute otherAttribute, CoverageCreator coverageCreator) throws FileNotFoundException  {
		List<Object> jsonValues = getJSONValues(otherAttribute, coverageCreator.getName());

		Iterator<EAttribute> it = cacheValues.keySet().iterator();
		while(it.hasNext()) {
			EAttribute eAttribute = it.next();
			for(Object o : cacheValues.get(eAttribute)) {
				if (o instanceof String) {
					String stringValue = (String) o;
					for(Object o2 : jsonValues) {
						if (o2 instanceof String) {
							String stringValue2 = (String) o2;
							if(stringValue.equals(stringValue2)) 
								return eAttribute;
						}
					}
				}
			}
		}

		return null;
	}

	/**
	 * Gets the values for a particular key
	 * 
	 * @param name
	 * @param sourceName
	 * @return
	 * @throws FileNotFoundException
	 */
	private List<Object> getJSONValues(EAttribute eAttribute, String sourceName) throws FileNotFoundException {
		if(eAttribute == null) 
			throw new IllegalArgumentException("EAttribute cannot be null");
		if(sourceName == null || sourceName.equals("")) 
			throw new IllegalArgumentException("sourceName cannot be null or empty");
		
		List<Object> result = new ArrayList<Object>();
		List<JsonElement> jsonDefs = this.sourceSet.getJsonSource(sourceName).getJsonDefs();
		if(jsonDefs.size() == 0) return result;
		JsonElement rootElement = jsonDefs.get(0); // TODO Consider all of them!
		
		List<JsonObject> elements = new ArrayList<JsonObject>();
		if (rootElement.isJsonArray()) {
			LOGGER.finest("Several objects found");
			for(int i = 0; i < rootElement.getAsJsonArray().size(); i++)
				if(rootElement.getAsJsonArray().get(i).isJsonObject())
					elements.add(rootElement.getAsJsonArray().get(i).getAsJsonObject());
		} else if(rootElement.isJsonObject()) {
			LOGGER.finest("Only one object found");
			elements.add(rootElement.getAsJsonObject());
		} else {
			LOGGER.finest("The root element was " + rootElement.getClass().getName());
			LOGGER.finest("It is: " + rootElement.getAsString());
		}
		
		String containmentElementName = eAttribute.eContainer().eClass().getName();
		String attributeName = eAttribute.getName();
		
		if(containmentElementName.equals(sourceName)) {
			// If the containmentElement name matches with the sourceName, 
			// we have to deal with the root elements of the JSON
			for(JsonObject jsonObject : elements) {
				Iterator<Map.Entry<String, JsonElement>> pairs = jsonObject.entrySet().iterator();
				while(pairs.hasNext()) {
					Map.Entry<String, JsonElement> pair = pairs.next();
					String key = pair.getKey();
					
					if(key.equals(attributeName) && pair.getValue().isJsonPrimitive() && pair.getValue().getAsJsonPrimitive().isString()) {
						result.add(pair.getValue().getAsJsonPrimitive().toString());
					} 
				}
			}
		} else {
			// Else, take the objects inside the JSON
			for(JsonObject jsonObject : elements) {
				Iterator<Map.Entry<String, JsonElement>> pairs = jsonObject.entrySet().iterator();
				while(pairs.hasNext()) {
					Map.Entry<String, JsonElement> pair = pairs.next();
					String key = pair.getKey();
					String jsonElementName = digestId(key);
					if (pair.getValue().isJsonObject() && jsonElementName.equals(containmentElementName)) {
						JsonObject jsonObject2 = pair.getValue().getAsJsonObject();
						Iterator<Map.Entry<String, JsonElement>> pairs2 = jsonObject2.entrySet().iterator();
						while(pairs2.hasNext()) {
							Map.Entry<String, JsonElement> pair2 = pairs2.next();
							String key2 = pair2.getKey();
							if(key2.equals(attributeName) && pair2.getValue().isJsonPrimitive() && pair2.getValue().getAsJsonPrimitive().isString()) {
								result.add(pair2.getValue().getAsJsonPrimitive().toString());
							} // TODO make this recursive!						
						}
					}
				}
			}
		}
		
		
		return result;
	}

	private String digestId(String id) {
		String result = id;
		if(result.endsWith("s")) 
			result = result.substring(0, result.length()-1);
		result = result.substring(0, 1).toUpperCase() + result.substring(1, result.length()); 
		return result;
	}
	
	private void saveEPackage(EPackage ePackage, File resultPath) {
		ResourceSet rset = new ResourceSetImpl();
		Resource res = rset.createResource(URI.createFileURI(resultPath.getAbsolutePath()));

		try {
			res.getContents().add(ePackage);
			res.save(null);
		} catch (IOException e) {
			e.printStackTrace();
		}
	}	
}
