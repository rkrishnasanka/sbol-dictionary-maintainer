package com.bbn.sd2;

import java.io.IOException;
import java.net.URI;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.logging.Logger;

import javax.mail.MessagingException;
import javax.xml.namespace.QName;

import org.json.JSONArray;

import org.sbolstandard.core2.Annotation;
import org.sbolstandard.core2.Collection;
import org.sbolstandard.core2.ComponentDefinition;
import org.sbolstandard.core2.GenericTopLevel;
import org.sbolstandard.core2.ModuleDefinition;
import org.sbolstandard.core2.SBOLConversionException;
import org.sbolstandard.core2.SBOLDocument;
import org.sbolstandard.core2.SBOLValidationException;
import org.sbolstandard.core2.TopLevel;
import org.synbiohub.frontend.SynBioHubException;

import com.bbn.sd2.DictionaryEntry.StubStatus;
import com.google.api.services.sheets.v4.model.CellFormat;
import com.google.api.services.sheets.v4.model.Color;
import com.google.api.services.sheets.v4.model.Request;
import com.google.api.services.sheets.v4.model.ValueRange;

/**
 * Helper class for importing SBOL into the working compilation.
 */
public final class MaintainDictionary {
    private static Logger log = Logger.getGlobal();

    private static final QName STUB_ANNOTATION = new QName("http://sd2e.org#","stub_object","sd2");
    private static final QName CREATED = new QName("http://purl.org/dc/terms/","created","dcterms");
    private static final QName MODIFIED = new QName("http://purl.org/dc/terms/","modified","dcterms");

    private static final String STAGING_DICTIONARY = "1xyFH-QqYzoswvI3pPJRlBqw9PQdlp91ds3mZoPc3wCU";

    /** The ID for the default Dictionary Spreadsheet, currently the "staging instance" */
    private static final String SD2E_DICTIONARY = STAGING_DICTIONARY;

    public static int synBioHubAccessRetryCount = 5;
    public static int synBioHubAccessRetryPauseMS = 1000;

    public static final String CHEBIPrefix = "http://identifiers.org/chebi/CHEBI:";


    /** Each spreadsheet tab is only allowed to contain objects of certain types, as determined by this mapping */
    private static Map<String, Set<String>> typeTabs = new HashMap<String,Set<String>>() {{
            put("Attribute", new HashSet<>(Arrays.asList("Attribute")));
            put("Reagent", new HashSet<>(Arrays.asList("Bead", "CHEBI", "DNA", "Protein", "RNA",
                                                       "Media", "Stain", "Buffer", "Solution")));
            put("Genetic Construct", new HashSet<>(Arrays.asList("DNA", "RNA")));
            put("Strain", new HashSet<>(Arrays.asList("Strain")));
            put("Protein", new HashSet<>(Arrays.asList("Protein")));
            put("Collections", new HashSet<>(Arrays.asList("Challenge Problem", "Collection")));
        }
            static final long serialVersionUID = 0;
        };

    /**
     * Frequency, in milliseconds, that email messages about invalid entries are generated
     */
    private static long invalidEntryNotifyPeriodMS = 7L * /* 7 Days */
                24L * /* 24 Hours */
                60L * /* 60 Minutes */
                60L * /* 60 Second */
                1000L; /* 1000 Milliseconds */

    /** Expected headers */
    private static final Set<String> validHeaders = new HashSet<>(Arrays.asList("Common Name", "Type", "SynBioHub URI",
                                                                                "Stub Object?", "Definition URI", "Status",
                                                                                "Definition URI / CHEBI ID",
                                                                                "Definition Import", "Last Updated"));

    private static final Set<String> protectedColumns = new HashSet<>(Arrays.asList("SynBioHub URI",
                                                                                    "Stub Object?", "Status"));

    /** These columns, along with the lab UID columns, will be checked for deleted cells that
     *  cause other cells to shift up */
    private static final Set<String> shiftCheckColumns = new HashSet<>(Arrays.asList("Common Name",
                                                                                     "Definition URI"));

    /** Classes of object that are implemented as a ComponentDefinition */
    private static Map<String,URI> componentTypes = new HashMap<String,URI>() {{
            put("Bead",URI.create("http://purl.obolibrary.org/obo/NCIT_C70671"));
            put("CHEBI",URI.create("http://identifiers.org/chebi/CHEBI:24431"));
            put("DNA",ComponentDefinition.DNA_REGION);
            put("Protein",ComponentDefinition.PROTEIN);
            put("RNA",ComponentDefinition.RNA_REGION);
        }
            static final long serialVersionUID = 0;
        };

    /** Classes of object that are implemented as a ModuleDefinition */
    private static Map<String,URI> moduleTypes = new HashMap<String,URI>(){{
            put("Strain",URI.create("http://purl.obolibrary.org/obo/NCIT_C14419"));
            put("Media",URI.create("http://purl.obolibrary.org/obo/NCIT_C85504"));
            put("Stain",URI.create("http://purl.obolibrary.org/obo/NCIT_C841"));
            put("Buffer",URI.create("http://purl.obolibrary.org/obo/NCIT_C70815"));
            put("Solution",URI.create("http://purl.obolibrary.org/obo/NCIT_C70830"));
        }
            static final long serialVersionUID = 0;
        };

    /** Classes of object that are implemented as a Collection.
     */
    private static Map<String,URI> collectionTypes = new HashMap<String,URI>(){{
            put("Challenge Problem",URI.create(""));
            put("Collection",URI.create(""));
        }
            static final long serialVersionUID = 0;
        };

    /** Classes of object that are not stored in SynBioHub, but are grounded in external definitions */
    private static Map<String,QName> externalTypes = new HashMap<String,QName>(){{
            put("Attribute",new QName("http://sd2e.org/types/#","attribute","sd2"));
        }
            static final long serialVersionUID = 0;
        };

    /** Email addresses mapping failures are sent to */
    private static Map<String,String> mappingFailureToList = null;
    private static Map<String,String> mappingFailureCCList = null;

    /** Email addresses entry failures are sent to */
    private static Map<String, String> entryFailuresList = null;

    /** The amount of time, in seconds, before notification email messages
     * for the mapping failures tab
     */
    private static final long minumumMappingFailureNotificationTime = 86400;

    /**
     * The maximum number of individual requests in a single Google request
     */
    private static int maxGoogleRequestCount = 50;

    /**
     * Standard XML date format for sbol objects
     */
    private static SimpleDateFormat sdfDate = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssX");


    /**
     * @param tab String name of a spreadsheet tab
     * @param type String naming a type
     * @return true if we know how to handle entries of this type
     */
    public static boolean validType(String tab, String type) {
        return typeTabs.get(tab).contains(type);
    }

    /**
     * @param tab String name of a spreadsheet tab
     * @return true if the given spreadsheet tab belongs to a predetermined set
     */
    public static boolean validTab(String tab) {
        return typeTabs.keySet().contains(tab);
    }

    public static final Set<String> headers() {
        Set<String> allValidHeaders = new HashSet<String>();

        allValidHeaders.addAll(validHeaders);
        allValidHeaders.addAll(DictionaryMaintainerApp.labUIDMap.keySet());

        return allValidHeaders;
    }

    public static final Set<String> getProtectedHeaders() {
        return protectedColumns;
    }

    public static Set<String> tabs() {
        return typeTabs.keySet();
    }

    public static String defaultSpreadsheet() {
        return SD2E_DICTIONARY;
    }

    public static String stagingSpreadsheet() {
        return STAGING_DICTIONARY;
    }

    public static Set<String> getAllowedTypesForTab(String tab) {
        return typeTabs.get(tab);
    }


    /** @return A string listing all valid types */
    public static String allTypes() {
        Set<String> s = new HashSet<>(componentTypes.keySet());
        s.addAll(moduleTypes.keySet());
        s.addAll(externalTypes.keySet());
        return s.toString();
    }

    private static boolean chebiTypeIsInRole(TopLevel entity, String type) {
        if(!type.equals("CHEBI")) {
            return false;
        }

        if(!(entity instanceof ComponentDefinition)) {
            return false;
        }

        ComponentDefinition cd = (ComponentDefinition)entity;

        for(URI cRoleURI : cd.getRoles()) {
            String cRole = cRoleURI.toString();
            if(cRole.startsWith(CHEBIPrefix)) {
                return true;
            }
        }

        return false;
    }

    public static URI getCHEBIURI(TopLevel entity) {
        if(!(entity instanceof ComponentDefinition)) {
            return null;
        }

        ComponentDefinition cd = (ComponentDefinition)entity;

        for(URI cTypeURI : cd.getTypes()) {
            String cType = cTypeURI.toString();
            if(cType.startsWith(CHEBIPrefix)) {
                return cTypeURI;
            }
        }

        return null;
    }

    private static boolean setCHEBIURI(TopLevel entity, URI newURI) {
        if(!(entity instanceof ComponentDefinition)) {
            return false;
        }

        ComponentDefinition cd = (ComponentDefinition)entity;

        Set<URI> typeList = new TreeSet<>();

        typeList.add(newURI);

        for(URI cTypeURI : cd.getTypes()) {
            String cType = cTypeURI.toString();
            if(!cType.startsWith(CHEBIPrefix)) {
                typeList.add(cTypeURI);
            }
        }

        try {
            cd.setTypes(typeList);
            return true;
        } catch(Exception e) {
            return false;
        }
    }

   private static boolean validateEntityType(TopLevel entity, String type) {
        if(componentTypes.containsKey(type)) {
            if(entity instanceof ComponentDefinition) {
                ComponentDefinition cd = (ComponentDefinition)entity;

                if(type.equals("CHEBI")) {
                    for(URI cTypeURI : cd.getTypes()) {
                        String cType = cTypeURI.toString();
                        if(cType.startsWith(CHEBIPrefix)) {
                            return true;
                        }
                    }
                }

                return cd.getTypes().contains(componentTypes.get(type));
            }
        } else if(moduleTypes.containsKey(type)) {
            if(entity instanceof ModuleDefinition) {
                ModuleDefinition md = (ModuleDefinition)entity;
                return md.getRoles().contains(moduleTypes.get(type));
            }
        } else if(externalTypes.containsKey(type)) {
            if(entity instanceof GenericTopLevel) {
                GenericTopLevel tl = (GenericTopLevel)entity;
                return tl.getRDFType().equals(externalTypes.get(type));
            }
        } else if(collectionTypes.containsKey(type)) {
            if (entity instanceof Collection)
                return true;
        } else {
            log.info("Don't recognize type "+type);
        }
        return false;
    }

    /**
     * Create a new dummy object
     * @param name Name of the new object, which will also be converted to a displayID and URI
     * @param type
     * @return
     * @throws Exception
     */
    private static SBOLDocument createStubOfType(String name, String type,
                                                 String timeStamp) throws SBOLValidationException,
                                                                          SynBioHubException,
                                                                          SBOLConversionException {
        SBOLDocument document = SynBioHubAccessor.newBlankDocument();
        String displayId = SynBioHubAccessor.sanitizeNameToDisplayID(name);
        TopLevel tl = null;
        if(componentTypes.containsKey(type)) {
            log.info("Creating stub Component for "+name);
            ComponentDefinition cd = document.createComponentDefinition(displayId, "1", componentTypes.get(type));
            cd.createAnnotation(STUB_ANNOTATION, "true");
            tl = cd;
        } else if(moduleTypes.containsKey(type)) {
            log.info("Creating stub Module for "+name);
            ModuleDefinition m = document.createModuleDefinition(displayId, "1");
            m.addRole(moduleTypes.get(type));
            m.createAnnotation(STUB_ANNOTATION, "true");
            tl = m;
        } else if(collectionTypes.containsKey(type)) {
            log.info("Creating stub Collection for "+name);
            Collection c = document.createCollection(displayId, "1");
            c.createAnnotation(STUB_ANNOTATION, "true");
            tl = c;
        } else if(externalTypes.containsKey(type)) {
            log.info("Creating definition placeholder for "+name);
            tl = document.createGenericTopLevel(displayId, "1", externalTypes.get(type));
        } else {
            log.info("Don't know how to make stub for type: "+type);
            return null;
        }
        // annotate with stub and creation information
        tl.setName(name);
        tl.createAnnotation(CREATED, timeStamp);
        tl.createAnnotation(MODIFIED, timeStamp);

        return document;
    }

    /** Get current date/time in standard XML format */
    public static String xmlDateTimeStamp() {
        // return current date/time
        return sdfDate.format(new Date());
    }

    /**
     * Clear all prior instances of an annotation and replace with the new one
     * @throws SBOLValidationException
     */
    private static void replaceOldAnnotations(TopLevel entity, QName key, String new_value) throws SBOLValidationException {
        Set<String> new_values = new HashSet<String>() {{ add(new_value); } static final long serialVersionUID = 0;};
        replaceOldAnnotations(entity, key, new_values);
    }

    /**
     * Clear all prior instances of an annotation and replace with a set of new annotations
     * @throws SBOLValidationException
     */
    private static void replaceOldAnnotations(TopLevel entity, QName key, Set<String> new_values) throws SBOLValidationException {
        while(entity.getAnnotation(key)!=null) {
            entity.removeAnnotation(entity.getAnnotation(key));
        }
        for (String value : new_values)
            entity.createAnnotation(key, value);
    }

    /**
     * Update a single dictionary entry, assumed to be valid
     * @param e entry to be updated
     * @return true if anything has been changed
     * @throws Exception
     */
    private static DictionaryEntry update_entry(DictionaryEntry e) throws SBOLConversionException, IOException, SBOLValidationException {
        assert(e.statusCode == StatusCode.VALID);

        // This is never called unless the entry is known valid
        URI local_uri = null;
        DictionaryEntry originalEntry = null;
        String synBioHubAction = null;

        // When reverseSync is true the spreadsheet is updated to
        // match the state in SynBioHub.
        boolean reverseSync = false;

        try {
            // If the URI is null and the name is not, attempt to resolve:
            if(e.uri==null && e.name!=null) {
                synBioHubAction = "resolve URI to name in SynBioHub";
                e.uri = SynBioHubAccessor.nameToURI(e.name);
                if(e.uri!=null) {
                    // This is an update to the spreadsheet, but not to symBioHub,
                    // so "changed" is not updated
                    e.spreadsheetUpdates.add(DictionaryAccessor.writeEntryURI(e, e.uri));
                }
            }

            Date now = new Date();
            // if the entry has no URI, create per type
            if(e.uri==null) {
                synBioHubAction = "create document for " + e.name + " in SynBioHub";
                e.document = createStubOfType(e.name, e.type, sdfDate.format(now));
                if(e.document==null) {
                    e.report.failure("Could not make object "+e.name, true);
                    e.statusCode = StatusCode.SBH_CONNECTION_FAILED;
                    return originalEntry;
                }
                // pull out the first (and only) element to get the URI
                local_uri = e.document.getTopLevels().iterator().next().getIdentity();
                synBioHubAction = "translate local URI";
                e.uri = SynBioHubAccessor.translateLocalURI(local_uri);
                e.report.success("Created stub in SynBioHub",true);
                e.spreadsheetUpdates.add(DictionaryAccessor.writeEntryURI(e, e.uri));
                e.changed = true;

            } else { // otherwise get a copy from SynBioHub
                synBioHubAction = "translate local URI";
                local_uri = SynBioHubAccessor.translateURI(e.uri);

                e.document = null;
                for(int i=0; i<synBioHubAccessRetryCount; ++i) {
                    try {
                        e.document = SynBioHubAccessor.retrieve(e.uri, false);
                    } catch(Exception exception) {
                    }
                }

                if(e.document == null) {
                    e.report.failure("Failed to retrieve linked object from SynBioHub");
                    e.statusCode = StatusCode.SBH_CONNECTION_FAILED;
                    return originalEntry;
                }

                originalEntry = new DictionaryEntry(e);
            }

            // Check if object belongs to the target Collection
            if(e.uri.equals(local_uri)) { // this condition occurs when the entry does not belong to the target collection, probably a more explicit and better way to check for it
                e.report.failure("Object does not belong to Dictionary collection " + SynBioHubAccessor.getCollectionID());
                e.statusCode = StatusCode.SBH_CONNECTION_FAILED;
                return originalEntry;
            }

            // Make sure we've got the entity to update in our hands:
            TopLevel entity = e.document.getTopLevel(local_uri);
            if(entity==null) {
                e.report.failure("Could not find or make object", true);
                e.statusCode = StatusCode.SBH_CONNECTION_FAILED;
                return originalEntry;
            }

            do {
                Annotation modifiedDateAnnotation = entity.getAnnotation(MODIFIED);

                if(modifiedDateAnnotation == null) {
                    break;
                }

                String modifiedDateStr = modifiedDateAnnotation.getStringValue();
                if(modifiedDateStr == null) {
                    break;
                }

                Date sheetDate = e.modifiedDate;

                if(!e.setModifiedDate(modifiedDateStr)) {
                    e.report.failure("Failed to parse entity modified date", true);
                    break;
                }

                if(sheetDate == null) {
                    break;
                }

                Date synBioHubDate = e.modifiedDate;

                if(synBioHubDate.after(sheetDate)) {
                    // Item was modifed in SynBioHub since dictionary edit
                    reverseSync = true;
                    e.dictionaryEntryChanged = true;
                }
            } while( false );

            if(e.type.equalsIgnoreCase("CHEBI")) {
                URI chebiURI = e.attributeDefinition;
                boolean updateSpreadsheetAttributeURI = false;

                if(chebiURI == null) {
                    // Spreadsheet does not have a value in the
                    // attribute URI column
                    updateSpreadsheetAttributeURI = true;
                    chebiURI = getCHEBIURI(entity);
                    if(chebiURI == null) {
                        if(originalEntry != null) {
                            originalEntry.attributeDefinition = null;
                        }

                        // Entity does not have a CHEBI URI
                        // Add the default CHEBI URI
                        try {
                            chebiURI = new URI(CHEBIPrefix + "24431");
                            setCHEBIURI(entity, chebiURI);
                            e.changed = true;

                        } catch(Exception exception) {
                        }
                    }

                    e.attributeDefinition = chebiURI;
                }

                if(!chebiURI.toString().startsWith(CHEBIPrefix)) {
                    // If the CHEBI URI in the spreadsheet does not
                    // start with the prefix, prepend the prefix
                    try {
                        chebiURI = new URI(CHEBIPrefix + chebiURI.toString());
                        updateSpreadsheetAttributeURI = true;
                    } catch(Exception e2) {
                        e2.printStackTrace();
                    }
                }

                // Compare the spreadsheet CHEBI URI to the SynBioHub
                // entity URI
                URI entityChebiURI = getCHEBIURI(entity);
                if(originalEntry != null) {
                    originalEntry.attributeDefinition = entityChebiURI;
                }
                if(!entityChebiURI.equals(chebiURI)) {
                    if(reverseSync) {
                        chebiURI = entityChebiURI;
                    } else {
                        setCHEBIURI(entity, chebiURI);
                        e.changed = true;
                    }
                    updateSpreadsheetAttributeURI = true;
                    e.report.note("Updated CHEBI URI", true);
                }

                if(updateSpreadsheetAttributeURI) {
                    try {
                        e.spreadsheetUpdates.add(DictionaryAccessor.
                                                 writeDefinitionOrCHEBIURI(e, chebiURI));
                        e.dictionaryEntryChanged = true;
                    } catch(Exception e2) {
                        e.report.failure("Failed to update Definition URI Column");
                    }
                }
            }

            // Check if typing is valid
            if(!validateEntityType(entity, e.type)) {
                if(chebiTypeIsInRole(entity, e.type)) {
                    e.statusCode = StatusCode.TYPE_IN_ROLE;
                } else {
                    e.statusCode = StatusCode.MISMATCH_TYPE;
                }

                return originalEntry;
            }

            // Note that the "stub" field is defined by the SynBioHub document.
            // The spreadsheet is updated to be consistent with the SynBioHub
            // document, but "changed" flag is not updated since the SynBioHub
            // document is not updated.
            if(e.attribute) {
                if(e.stub != StubStatus.UNDEFINED) {
                    e.stub = StubStatus.UNDEFINED;
                    e.spreadsheetUpdates.add(DictionaryAccessor.writeEntryStub(e, e.stub));
                    e.dictionaryEntryChanged = true;
                }
            } else {
                boolean entity_is_stub = (entity.getAnnotation(STUB_ANNOTATION) != null);
                if((entity_is_stub && e.stub!=StubStatus.YES) || (!entity_is_stub && e.stub!=StubStatus.NO)) {
                    e.stub = entity_is_stub ? StubStatus.YES : StubStatus.NO;
                    e.spreadsheetUpdates.add(DictionaryAccessor.writeEntryStub(e, e.stub));
                    e.report.note(entity_is_stub?"Stub object":"Linked with non-stub object", true);
                    e.dictionaryEntryChanged = true;
                }
            }

            // update entity name if needed
            if(e.name!=null && !e.name.equals(entity.getName())) {
                if(originalEntry != null) {
                    originalEntry.name = entity.getName();
                }

                if(reverseSync) {
                    ValueRange update =
                        DictionaryAccessor.writeCellData(e, "Common Name",
                                                         entity.getName());
                    e.spreadsheetUpdates.add(update);
                } else {
                    entity.setName(e.name);
                    e.changed = true;
                    e.report.success("Name changed to '"+e.name+"'",true);
                }
            }

            // if the entry has lab entries, check if they match and (re)annotate if different
            for(String labKey : e.labUIDs.keySet()) {
                // Extra lab ids from entry (spreadsheet)
                Set<String> labIds = new HashSet<String>();

                Set<String> labEntries = e.labUIDs.get(labKey);
                if(labEntries != null) {
                    labIds.addAll(labEntries);
                }

                // Extract lab ids from SynBioHub
                Set<String> synBioHubIds = new HashSet<String>();

                QName labQKey = new QName("http://sd2e.org#",labKey,"sd2");
                List<Annotation> annotations = entity.getAnnotations();
                for (Annotation ann : annotations) {
                    if(ann.getQName().equals(labQKey)) {
                        synBioHubIds.add(ann.getStringValue());
                    }
                }

                // Compare lab ids
                if(!labIds.equals(synBioHubIds)) {
                    if(originalEntry != null) {
                        // Update originalEntry with values from SynBioHub
                        originalEntry.labUIDs.put(labKey, synBioHubIds);
                    }

                    if(reverseSync) {
                        // Populate the spreadsheet with lab ids from SynBioHub
                        String labIdStr = "";
                        for(String labId : synBioHubIds) {
                            if(labIdStr.length() > 0) {
                                labIdStr += ", ";
                            }

                            labIdStr += labId;
                        }

                        String labUIDLabel =
                            DictionaryMaintainerApp.reverseLabUIDMap.get(labKey);

                        ValueRange update =
                            DictionaryAccessor.writeCellData(e, labUIDLabel,
                                                             labIdStr);

                        e.spreadsheetUpdates.add(update);

                    } else {
                        // Populate SybBioHub with lab ids from the spreadsheet
                        replaceOldAnnotations(entity, labQKey, labIds);
                        e.changed = true;
                    }

                    if(labIds.size() > 0)
                        e.report.success(labKey+" for " + e.name + " is "+String.join(", ", labIds),true);
                    else
                        e.report.success("Deleted " + labKey + " for " + e.name, true);
                }
            }

            if(!e.type.equalsIgnoreCase("CHEBI")) {
                // Non-CHEBI Entry, Definition URI column is present
                Set<URI> derivations = entity.getWasDerivedFroms();
                URI entityDerivation = null;

                if(derivations.size() > 0) {
                    // This is the Definition URI in SynBioHub
                    entityDerivation = derivations.iterator().next();
                }

                if(e.definitionURIColumn != null) {
                    if(originalEntry != null) {
                        originalEntry.attributeDefinition = entityDerivation;
                    }

                    if(e.attributeDefinition == null) {
                        // Spreadsheet Definition URI is empty

                        if(entityDerivation != null) {
                            // SynBioHub Definition URI is set
                            if(reverseSync) {
                                try {
                                    e.spreadsheetUpdates.add(DictionaryAccessor.
                                                             writeDefinitionOrCHEBIURI(e, entityDerivation));
                                } catch(Exception exception) {
                                }

                                try {
                                    e.spreadsheetUpdates.add(DictionaryAccessor.
                                                             writeEntryDefinition(e, entityDerivation));
                                } catch(Exception exception) {
                                }

                            } else {
                                // SynBioHub has a Definition URI, but the
                                // spreadsheet does not have one
                                // Remove Definition URI from SynBioHub entity
                                derivations.clear();
                                entity.setWasDerivedFroms(derivations);
                                e.changed = true;
                                e.report.success("Definition for " + e.name + " was removed.", true);
                            }
                        }

                    } else {
                        // Spreadsheet has a Definition URI
                        if((entityDerivation == null) || !e.attributeDefinition.equals(entityDerivation)) {
                            // SynBioHub Definitino URI does not match
                            if(reverseSync) {
                                URI definitionURI = null;
                                if(entityDerivation == null) {
                                    try {
                                        definitionURI = new URI("");
                                    } catch(Exception uriFormatException) {
                                    }
                                } else {
                                    definitionURI = entityDerivation;
                                }

                                try {
                                    e.spreadsheetUpdates.add(DictionaryAccessor.
                                                             writeDefinitionOrCHEBIURI(e, definitionURI));
                                } catch(Exception exception) {
                                }

                                try {
                                    e.spreadsheetUpdates.add(DictionaryAccessor.
                                                             writeEntryDefinition(e, definitionURI));
                                } catch(Exception exception) {
                                }
                            } else {
                                // Populate the derived from property in the
                                // SynBioHub entry with the value of the
                                // Definition URI entry in the spreadsheet.
                                derivations.clear();
                                derivations.add(e.attributeDefinition);
                                entity.setWasDerivedFroms(derivations);
                                e.changed = true;
                                e.report.success("Definition for "+e.name+" is '"+e.attributeDefinition+"'",true);
                            }
                        }
                    }
                } else {
                    // Update the spreadsheet Import Definition column with the
                    // SynBioHub value
                    ValueRange update =
                        DictionaryAccessor.writeDefinitionImport(e, entityDerivation);

                    if(update != null) {
                        e.spreadsheetUpdates.add(update);
                    }
                }
            }

            if(e.changed) {
                e.modifiedDate = now;
                replaceOldAnnotations(entity, MODIFIED, e.getModifiedDate());
            }
            String entryUpdateTime = e.getModifiedDate();

            if(entryUpdateTime != null) {
                String sheetUpdateTime = DictionaryAccessor.readLastUpdated(e);
                if((sheetUpdateTime == null) ||
                   !sheetUpdateTime.equals(entryUpdateTime)) {
                    // Update the time stamp in the spreadsheet
                    ValueRange update = DictionaryAccessor.writeLastUpdated(e, entryUpdateTime);
                    if(update != null) {
                        e.spreadsheetUpdates.add(update);
                        e.dictionaryEntryChanged = true;
                    }
                }
            }

        } catch (SynBioHubException exception) {
            log.severe(exception.getMessage());
            throw new IOException("SynBioHub transaction failed when trying to "
                                  + synBioHubAction);
        }

        return originalEntry;
    }

    private static Map< String, Map<String, String>> generateFieldMap(List<DictionaryEntry> entries) {
        Map<String, Map<String, String>> retVal = new TreeMap< String, Map<String, String>>();

        for(DictionaryEntry entry : entries) {
            if(entry.uri == null) {
                continue;
            }
            String uri = entry.uri.toString();

            Map<String, String> fieldMap = entry.generateFieldMap();
            retVal.put(uri, fieldMap);
        }

        return retVal;
    }

    private static void checkShifts(List<DictionaryEntry> currentEntries,
                                    List<DictionaryEntry> originalEntries) throws Exception {
        // Extract spreadsheet data into a map
        Map< String, Map<String, String>> originalEntryMap = generateFieldMap(originalEntries);

        // allShiftCheckColumns contains the headers of the columns that are
        // checked for value shifts (i.e. deleted cells)
        Set<String> allShiftCheckColumns = new HashSet<>(shiftCheckColumns);
        for(String labUIDTag : DictionaryMaintainerApp.labUIDMap.keySet()) {
            allShiftCheckColumns.add(labUIDTag);
        }

        // This code looks for deleted cells that caused the remaining
        // cells in the column to shift up.  For each column,
        // "maxShifts" defines the minimum number of cell value shifts
        // that prevent updates from being committed
        final int maxShifts = 3;
        Map<String, Integer> upShiftCounts = null;
        String tab = null;

        // Loop through spreadsheet rows
        Map<String, String> previousRowValues = null;
        for(DictionaryEntry e : currentEntries) {
            if(e.tab != tab) {
                // Starting a new tab
                tab = e.tab;
                // Keeps track of shift counts for each column in this tab
                upShiftCounts = new HashMap<String, Integer>();
                previousRowValues = null;
            }

            if(e.uri == null) {
                log.severe("Row " + e.row_index + " of tab \"" + e.tab
                           + "\" is missing a uri ");
                continue;
            }

            // Find field values from last SynBioHub update
            Map<String, String> originalValues = originalEntryMap.get(e.uri.toString());
            if(originalValues == null) {
                continue;
            }

            // Generate map of field values in this spreadsheet row
            Map<String, String> currentValues = e.generateFieldMap();

            // Ensure we have the field values from the row above
            // the current row
            if(previousRowValues == null) {
                // No information about the value in the row above
                previousRowValues = currentValues;
                continue;
            }

            // Loop through columns in this row to check for value shifts
            for(String key : allShiftCheckColumns) {
                String currentValue = currentValues.get(key);
                if(currentValue == null) {
                    // This row does not contain a value in column "key"
                    continue;
                }

                String originalValue = originalValues.get(key);
                if(originalValue == null) {
                    // The value in this cell was just created, and therefore
                    // does not have previous value it changed from
                    continue;
                }

                if(originalValue.equals(currentValue)) {
                    // This value in this cell is consistent with SynBioHub
                    continue;
                }

                String previousRowValue = previousRowValues.get(key);
                if(previousRowValue == null) {
                    // No value for cell directly above
                    continue;
                }

                if(previousRowValue.length() == 0) {
                        continue;
                }

                if(previousRowValue.equals(originalValue)) {
                    // The value has shifted up a row
                    Integer count = upShiftCounts.get(key);
                    if(count == null) {
                        upShiftCounts.put(key, 1);
                    } else {
                        upShiftCounts.put(key, count + 1);
                    }
                    count = upShiftCounts.get(key);
                    if(count == maxShifts) {
                        String errMsg = "Found potential shift in column \"" +
                            key + "\" of tab \"" + tab + "\"";
                        log.severe(errMsg);
                        throw new Exception(errMsg);
                    }
                }

            }

            previousRowValues = currentValues;
        }
    }

    public static Color makeColor(int red, int green, int blue) {
        Color newColor = new Color();

        newColor.setAlpha(1.0f);
        newColor.setRed((float)red / 255.0f);
        newColor.setGreen((float)green / 255.0f);
        newColor.setBlue((float)blue / 255.0f);

        return newColor;
    }

    public static Color greenColor() {
        return makeColor(0, 144, 81);
    }

    public static Color redColor() {
        return makeColor(148, 17, 0);
    }

    public static Color grayColor() {
        return makeColor(146, 146, 146);
    }

    private static List<MappingFailureEntry> getMappingFailures() throws IOException {
        List<MappingFailureEntry> entries = new ArrayList<>();

        // First, read the data from the Mapping Failures tab
        ValueRange tabData = DictionaryAccessor.getTabData("Mapping Failures");

        List<List<Object>> values = tabData.getValues();
        if(values == null) {
            return entries;
        }

        List<Object> columnHeaders = values.get(1);

        // Make sure there is a "Status" Column
        Set<String> columnHeaderSet = new TreeSet<>();
        for(Object columnHeader : columnHeaders) {
            columnHeaderSet.add((String)columnHeader);
        }

        if(!columnHeaderSet.contains("Status")) {
            throw new IOException("Mapping Failures tab does not contain a Status column");
        }

        // Create data structures from the spreadsheet data
        for(int i=2; i<values.size(); ++i) {
            List<Object> rowData = values.get(i);

            Map<String, String> rowEntries = new HashMap<>();

            for(int j=0; j<rowData.size(); ++j) {
                String columnHeader = (String)columnHeaders.get(j);
                String cellValue = (String)rowData.get(j);

                rowEntries.put(columnHeader, cellValue);
            }

            entries.add(new MappingFailureEntry(rowEntries, i));
        }

        return entries;
    }

    private static MappingFailureEmailContent
        generateNotificationReport(Map<String, List<MappingFailureEntry>> itemToExperiments) {

        // Sort item list
        Set<String> keySet = itemToExperiments.keySet();
        String[] keyArray = keySet.toArray(new String[0]);
        Arrays.sort(keyArray);

        String lab = itemToExperiments.get(keyArray[0]).get(0).getLab();

        Date notificationDate = new Date();

        // Build content of notification email
        String notificationReport = "Mapping Failures for " + lab + ":\n";

        JSONArray ja = new JSONArray();

        for(String item : keyArray) {
            List<MappingFailureEntry> itemEntries = itemToExperiments.get(item);

            // Sort entries by experiment name
            class CompareExperiments implements Comparator<MappingFailureEntry> {
                @Override
                public int compare(MappingFailureEntry entry1, MappingFailureEntry entry2) {
                    return entry1.getExperiment().compareTo(entry2.getExperiment());
                }
            }

            Collections.sort(itemEntries, new CompareExperiments());

            notificationReport += "\n\t" + item + ", " +
                itemEntries.get(0).getItemId() + "\n";
            for(MappingFailureEntry entry : itemEntries) {
                ja.put( entry.toJSON() );
                entry.setLastNotificationTime(notificationDate);
                notificationReport += "\t\t" + entry.getExperiment() + " - row " +
                    entry.getRow() + "\n";
            }
        }

        MappingFailureEmailContent email = null;
        if(notificationReport != null) {
            email = new MappingFailureEmailContent();
            email.content = notificationReport;
            email.attachmentData = ja.toString(2).getBytes();
        }

        return email;
    }

    private static void batchUpdateValues(List<ValueRange> values) throws IOException {
        for(int i=0; i<values.size(); i += maxGoogleRequestCount) {
            int endIndex = i + maxGoogleRequestCount;

            if(endIndex > values.size()) {
                endIndex = values.size();
            }

            List<ValueRange> requests = values.subList(i, endIndex);
            DictionaryAccessor.batchUpdateValues(requests);
        }
    }

    private static void batchUpdateRequests(List<Request> requestList) throws IOException {
        for(int i=0; i<requestList.size(); i += maxGoogleRequestCount) {
            int endIndex = i + maxGoogleRequestCount;

            if(endIndex > requestList.size()) {
                endIndex = requestList.size();
            }

            List<Request> requests = requestList.subList(i, endIndex);
            DictionaryAccessor.batchUpdateRequests(requests);
        }
    }

    private static void updateMappingFailuresTab(List<MappingFailureEntry> entries,
                                                 char statusColumn) throws IOException {
        List<ValueRange> updates = new ArrayList<>();

        // Update the Status column in the Mapping Failures tab
        for(MappingFailureEntry entry : entries) {
            if(!entry.getNotified() && entry.getValid()) {
                continue;
            }

            String location = "Mapping Failures!" + statusColumn + entry.getRow();
            String status = entry.getStatus();
            ValueRange valueRange = DictionaryAccessor.writeLocationText(location, status);
            updates.add(valueRange);
        }

        if(!updates.isEmpty()) {
            batchUpdateValues(updates);
        }
    }

    static class MappingFailureEmailContent {
        public MappingFailureEmailContent() {
            content = null;
            attachmentData = null;
        }

        public String content;
        public byte[] attachmentData;
    }

    // Find the spreadsheet column of the Status column in the mapping
    // failures tab
    private static char getMappingFailuresStatusColumn() throws IOException {
        ValueRange columnHeaders = DictionaryAccessor.getTabData("Mapping Failures!2:2");
        List<List<Object>> values = columnHeaders.getValues();

        if(values == null) {
            throw new IOException("Did not find headers in Mapping Failures tab");
        }

        char columnName = 'A';

        for(Object stringObject : values.get(0)) {
            if(stringObject == null) {
                continue;
            }

            String headerName = (String)stringObject;
            if(headerName.equals("Status")) {
                return columnName;
            }

            columnName = (char)(columnName + 1);
        }

        throw new IOException("Did not find Status column in Mapping Failures tab");
    }

    private static MappingFailureEmailContent
        findMappingFailuresForLab(List<MappingFailureEntry> entries) throws IOException {

        if(entries.isEmpty()) {
            return null;
        }

        Map<String, List<MappingFailureEntry>> itemToExperiments =
            new TreeMap<String, List<MappingFailureEntry>>();

        Date now = new Date();
        long timeSinceLastNotification = now.getTime() / 1000L;

        // Associate experiments with items
        for(MappingFailureEntry entry : entries) {
            if(!entry.getValid()) {
                continue;
            }

            String item = entry.getItem();

            List<MappingFailureEntry> experimentList = itemToExperiments.get(item);
            if(experimentList == null) {
                experimentList = new ArrayList<>();
                itemToExperiments.put(item, experimentList);
            }

            experimentList.add(entry);

            // Keep track of the most recent time a notification email
            // message was sent
            long timeSinceNotification = entry.secondsSinceLastNotification(now);
            if(timeSinceNotification < timeSinceLastNotification) {
                timeSinceLastNotification = timeSinceNotification;
            }
        }

        if(timeSinceLastNotification < minumumMappingFailureNotificationTime) {
            return null;
        }

        if(itemToExperiments.isEmpty()) {
            return null;
        }

       return generateNotificationReport(itemToExperiments);
    }

    private static Map<String, List<MappingFailureEntry>>
        generateMappingFailureLabEntries(List<MappingFailureEntry> entries)
        throws IOException  {

        // Sort entries by labs
        Map<String, List<MappingFailureEntry>> labEntries = new TreeMap<>();

        for(MappingFailureEntry entry : entries) {
            String lab = entry.getLab();

            List<MappingFailureEntry> labEntryList = labEntries.get(lab);
            if(labEntryList == null) {
                labEntryList = new ArrayList<>();
                labEntries.put(lab, labEntryList);
            }

            labEntryList.add(entry);
        }

        return labEntries;
    }

    private static MappingFailureEmailContent
        resolveMappingFailuresForLab(List<MappingFailureEntry> labMappingFailures,
                                     List<MappingFailureEntry> allMappingFailures,
                                     List<DictionaryEntry> dictionaryEntries)
        throws IOException {

        // This is the first row in the mapping failures sheet,
        // indexed starting at 1
        final int firstDataRow = 3;

        String notification = null;

        if(labMappingFailures.isEmpty()) {
            return null;
        }

        // Extract lab name
        String lab = labMappingFailures.get(0).getLab();

        // Construct a Set of all Lab ids from this lab;
        Set<String> labIds = new TreeSet<>();
        Map<String, DictionaryEntry> idToEntry = new TreeMap<>();

        for(DictionaryEntry entry : dictionaryEntries) {
            Set<String> itemIds = entry.itemIdsForLabUID(lab);
            if(itemIds == null) {
                continue;
            }

            for(String itemId : itemIds) {
                idToEntry.put(itemId, entry);
            }

            labIds.addAll( itemIds );
        }

        // Construct a set of the row from the Mapping Failures tab
        // to be deleted.  Also keep track of the first row to be
        // deleted
        Set<Integer> rowsToDelete = new TreeSet<>();
        int firstRowToDelete = allMappingFailures.size() + firstDataRow - 1;

        for(int i=0; i<labMappingFailures.size(); ++i) {
            MappingFailureEntry mEntry = labMappingFailures.get(i);

            if(!mEntry.getValid()) {
                continue;
            }

            String itemId = mEntry.getItemId();
            if(mEntry.getRow() < firstRowToDelete) {
                firstRowToDelete = mEntry.getRow();
            }

            DictionaryEntry dEntry = idToEntry.get( itemId );
            if(dEntry == null) {
                continue;
            }

            rowsToDelete.add(mEntry.getRow());
        }

        if(rowsToDelete.size() == 0) {
            return null;
        }

        if(rowsToDelete.size() == 1) {
            notification = "The following mapping failure was resolved:\n\n";
        } else {
            notification = "The following mapping failures were resolved:\n\n";
        }

        // Data in rows that were deleted
        JSONArray ja = new JSONArray();

        // Remove rows in rowsToDelete set
        List<Request> deleteRequests = new ArrayList<>();

        int decrementDelta = 0;
        for(int i=(firstRowToDelete-firstDataRow); i<allMappingFailures.size(); ++i) {
            MappingFailureEntry mEntry = allMappingFailures.get(i);
            mEntry.decrementRow(decrementDelta);

            int deleteIndex = i + firstDataRow + decrementDelta;
            if(!rowsToDelete.contains(deleteIndex)) {
                continue;
            }

            ja.put( mEntry.toJSON() );

            // Generate request to delete the row from the spreadsheet
            // Note that Google API indexes rows starting at zero, so
            // one is subtracted from the row index
            Request req = DictionaryAccessor.deleteRowRequest("Mapping Failures",
                                                              mEntry.getRow() - 1);
            deleteRequests.add( req );

            notification += mEntry.getExperiment() + "," +
                mEntry.getLab() + "," +
                mEntry.getItem() + "," +
                mEntry.getItemId() + "\n";

            // Remove mapping failure entry
            allMappingFailures.remove( i-- );
            ++decrementDelta;
        }

        batchUpdateRequests(deleteRequests);

        MappingFailureEmailContent email = null;
        if(notification != null) {
            email = new MappingFailureEmailContent();
            email.content = notification;
            email.attachmentData = ja.toString(2).getBytes();
        }

        return email;
    }

    /*
     * Generates notification emails for entries in the mapping
     * failures tab.
     *
     * Returns a map that maps lab names to the contents of a
     * notification email for that lab.
     */
    private static Map<String, MappingFailureEmailContent>
        generateMappingFailureEmails(List<MappingFailureEntry> entries) throws IOException {

        Map<String, MappingFailureEmailContent> notifications = new TreeMap<>();

        Map<String, List<MappingFailureEntry>> labEntries =
            generateMappingFailureLabEntries(entries);

        for(String lab : labEntries.keySet()) {
            MappingFailureEmailContent emailContent = findMappingFailuresForLab(labEntries.get(lab));
            if(emailContent != null) {
                notifications.put(lab, emailContent);
            }
        }

        char statusColumn = getMappingFailuresStatusColumn();

        updateMappingFailuresTab(entries, statusColumn);

        return notifications;
    }

    /*
     * Generates notification emails for resolution of entries in the
     * mapping failures tab.
     *
     * Returns a map that maps lab names to the contents of a
     * notification email for that lab.
     */
    private static Map<String, MappingFailureEmailContent>
        generateMappingFailureResolutionEmails(List<MappingFailureEntry> mappingFailures,
                                               List<DictionaryEntry> dictionaryEntries)
        throws IOException {

        Map<String, MappingFailureEmailContent> notifications = new TreeMap<>();

        Map<String, List<MappingFailureEntry>> labEntries =
            generateMappingFailureLabEntries(mappingFailures);

        for(String lab : labEntries.keySet()) {
            MappingFailureEmailContent emailContent =
                resolveMappingFailuresForLab(labEntries.get(lab),
                                             mappingFailures,
                                             dictionaryEntries);
            if(emailContent != null) {
                notifications.put(lab, emailContent);
            }
        }

        return notifications;
    }

    /**
     * Process mapping failures tab
     */
    private static void processMappingFailures(List<DictionaryEntry> dictionaryEntries) throws IOException {
        List<MappingFailureEntry> entries = getMappingFailures();

        Map<String, MappingFailureEmailContent>
            notifications = generateMappingFailureResolutionEmails(entries, dictionaryEntries);
        if(notifications != null) {
            for(String lab : notifications.keySet()) {
                String toList = null;
                if(mappingFailureToList != null) {
                    toList = mappingFailureToList.get(lab);
                }

                String ccList = null;
                if(mappingFailureCCList != null) {
                    ccList = mappingFailureCCList.get(lab);
                }

                if(toList != null) {
                    try {
                        MappingFailureEmailContent email = notifications.get(lab);
                        DictionaryAccessor.sendEmail(toList, ccList,
                                                     "Mapping Failure Resolutions",
                                                     email.content,
                                                     email.attachmentData);
                        if(ccList != null) {
                            log.info("Sent mapping failure resolution email notification for "
                                     + lab + " to " + toList + " with cc " + ccList);
                        } else {
                            log.info("Sent mapping failure resolution email notification for "
                                     + lab + " to " + toList);
                        }
                    } catch(MessagingException e) {
                        if(ccList != null ) {
                            log.warning("Failed to send mapping failure resolution email notification for "
                                        + lab + " to " + toList + " with cc " + ccList);
                        } else {
                            log.warning("Failed to send mapping failure resolution email notification for "
                                        + lab + " to " + toList);
                        }

                        e.printStackTrace();
                    }
                }
            }
        }

        notifications = generateMappingFailureEmails(entries);
        if(notifications != null) {
            for(String lab : notifications.keySet()) {
                String toList = null;
                if(mappingFailureToList != null) {
                    toList = mappingFailureToList.get(lab);
                }

                String ccList = null;
                if(mappingFailureCCList != null) {
                    ccList = mappingFailureCCList.get(lab);
                }

                if(toList != null) {
                    try {
                        MappingFailureEmailContent email = notifications.get(lab);
                        DictionaryAccessor.sendEmail(toList, ccList,
                                                     "Mapping Failures Report",
                                                     email.content,
                                                     email.attachmentData);
                        if(ccList != null) {
                            log.info("Sent mapping failure report email notification for "
                                     + lab + " to " + toList + " with cc " + ccList);
                        } else {
                            log.info("Sent mapping failure report email notification for "
                                     + lab + " to " + toList);
                        }
                    } catch(MessagingException e) {
                        if(ccList != null) {
                            log.warning("Failed to send mapping failure report email notification for "
                                        + lab + " to " + toList + " with cc " + ccList);
                        } else {
                            log.warning("Failed to send mapping failure report email notification for "
                                        + lab + " to " + toList);
                        }

                        e.printStackTrace();
                    }
                }
            }
        }
    }

    private static int colorFloatToInt(Float f) {
        if(f == null) {
                return 0;
        }

        return (int)Math.round(f * 255.0);
    }

    public static boolean colorsEqual(Color c1, Color c2) {
        if(colorFloatToInt(c1.getRed()) != colorFloatToInt(c2.getRed())) {
                return false;
        }

        if(colorFloatToInt(c1.getBlue()) != colorFloatToInt(c2.getBlue())) {
                return false;
        }

        if(colorFloatToInt(c1.getGreen()) != colorFloatToInt(c2.getGreen())) {
                return false;
        }

        return true;
    }

    private static List<DictionaryEntry> findFailuresToEmail(List<DictionaryEntry> updates, long lastEmailTime) {
        Date now = new Date();

        List<DictionaryEntry> emailEntries = new ArrayList<>();
        boolean sendEmail = false;

        if((now.getTime() - lastEmailTime) > invalidEntryNotifyPeriodMS) {
            sendEmail = true;
        }

        // Generate email messages for invalid entries
        for(DictionaryEntry e : updates) {
            if(colorsEqual(e.statusColor, redColor())) {

                if(sendEmail) {
                    // Generate an email message for this entry
                    e.lastNotifyTime = now;
                    emailEntries.add(e);
                }

                if(e.lastNotifyTime.getTime() > 0) {
                    // Make sure the status field records the last email notification time
                    e.addNotificationDateToStatus();
                }
            }
        }

        return emailEntries;
    }

    private static void sendEntryFailureEmails(List<DictionaryEntry> failuresToEmail)
        throws IOException,MessagingException {

        if(failuresToEmail.isEmpty()) {
            return;
        }

        if(entryFailuresList == null) {
            return;
        }

        String toList = entryFailuresList.get("To");
        String ccList = entryFailuresList.get("CC");

        if(toList == null) {
            return;
        }

        String emailContent = "";
        for(DictionaryEntry entry : failuresToEmail) {
            emailContent += entry.tab + " tab, row "
                + entry.row_index + ", "
                + entry.report.toString() + "\r\n\r\n";
        }

        DictionaryAccessor.sendEmail(toList, ccList,
                                     "SD2 Dictionary Entry Errors",
                                     emailContent, null);

        if(ccList == null) {
            log.info("Sent entry failure email to " + toList);
        } else {
            log.info("Sent entry failure email to " + toList +
                     ", with cc " + ccList);
        }

    }

    /**
     * Run one pass through the dictionary, updating all entries as needed
     */
    public static void maintain_dictionary(Map<String, Map<String, String>> emailLists) {
        Color green = greenColor();
        Color red = redColor();
        Color gray = grayColor();

        // Extract email address lists for mapping failure tab notifications
        mappingFailureToList = emailLists.get("MappingFailuresTo");
        mappingFailureCCList = emailLists.get("MappingFailuresCC");
        entryFailuresList = emailLists.get("EntryFailures");

        // A list of all the dictionary entries in all the tabs
        List<DictionaryEntry> allTabEntries = new ArrayList<>();

        // Maps the tab name to the dictionary entries in that tab
        Map<String, List<DictionaryEntry>> tabEntries = new TreeMap<>();

        // Read the Google spreadsheet tabs
        try {
            log.info("Beginning dictionary update");

            // Certain properties, such as "sheet id" numbers for the
            // tabs do change during the processing and are used in
            // several places.  This caches the values to limit the
            // number of requests to Google
            DictionaryAccessor.cacheSheetProperties();

            for(String tab : MaintainDictionary.tabs()) {
                // This caches the location of the the columns in the
                // tab
                DictionaryAccessor.cacheTabHeaders(tab);

                // Read the dictionary entries from the tab
                List<DictionaryEntry> entries =
                    DictionaryAccessor.snapshotCurrentDictionary(tab);

                tabEntries.put(tab, entries);
                allTabEntries.addAll(entries);
            }

            DictionaryAccessor.cacheTabHeaders("Mapping Failures");

        } catch(Exception e) {
            UpdateReport report = new UpdateReport();
            e.printStackTrace();
            report.failure("Failed to read dictionary spreadsheet: " + e.getMessage());
            try {
                DictionaryAccessor.writeStatusUpdate("SD2 Dictionary ("
                                                     + DictionaryMaintainerApp.VERSION
                                                     + ") "
                                                     + report.toString());
            } catch(Exception e2) {
                e2.printStackTrace();
            }
            return;
        }

        try {
            log.info("Checking protections ...");
            DictionaryAccessor.checkProtections();
            log.info("Finished checking protections ...");

        } catch(Exception e) {
            e.printStackTrace();
        }

        // Check for duplicate names.  Dictionary entries with
        // duplicate names are marked as invalid
        DictionaryAccessor.validateUniquenessOfEntries("Common Name", allTabEntries);
        for(String uidTag : DictionaryMaintainerApp.labUIDMap.keySet()) {
            DictionaryAccessor.validateUniquenessOfEntries(uidTag, allTabEntries);
        }

        // List of entries that failed that need to be included in
        // notification email messages
        List<DictionaryEntry> failuresToEmail = new ArrayList<>();

        for(String tab : MaintainDictionary.tabs()) {
            int rangeId = -1;
            UpdateReport report = new UpdateReport();
            int mod_count = 0, bad_count = 0, io_failure_count = 0;

            // This will contain updates to be made to the spreadsheet
            List<ValueRange> spreadsheetUpdates = new ArrayList<ValueRange>();

            // This will contain the status column formatting updates
            List<Request> statusFormattingUpdates = new ArrayList<>();

            log.info("Processing \"" + tab + "\" tab");

            try {
                // These are the entries according to the spreadsheet
                List<DictionaryEntry> spreadsheetEntries = tabEntries.get(tab);

                // This will contain the SynBioHub view of the
                // dictionary entries
                List<DictionaryEntry> synBioHubEntries = new ArrayList<DictionaryEntry>();

                // This contains the entries as they were before being
                // updated by the dictionary application.  The map is
                // indexed by the entry row
                Map<Integer, DictionaryEntry> initialEntryMap = new TreeMap<>();

                // This contains the updated entries.  It is indexed
                // by the entry row.
                Map<Integer, DictionaryEntry> updatedEntryMap = new TreeMap<>();

                long soonestNotifyTime = 0;


                // Loop through the spreadsheet rows
                for(DictionaryEntry e : spreadsheetEntries) {
                    DictionaryEntry synBioHubEntry = null;

                    // Save a copy of the entry before it is updated
                    initialEntryMap.put(e.row_index, new DictionaryEntry(e));

                    if (e.statusCode == StatusCode.VALID) {
                        // At this point the spreadsheet row has passed some rudimentary
                        // sanity checks.  The following method fetches or creates the
                        // corresponding SBOL Document and updates the document according
                        // to the spreadsheet row.  The method returns the "original"
                        // spreadsheet row, based on data in SynBioHub
                        synBioHubEntry = update_entry(e);
                    }

                    if(e.lastNotifyTime.getTime() > soonestNotifyTime) {
                        soonestNotifyTime = e.lastNotifyTime.getTime();
                    }

                    Color statusColor;
                    if(e.statusCode == StatusCode.VALID) {
                        // This row looks good
                        if(synBioHubEntry != null) {
                            // Save original (SynBioHub) entry
                            synBioHubEntries.add(synBioHubEntry);
                        }

                        statusColor = green;
                    } else {
                        // There is a problem with this row
                        switch (e.statusCode) {
                        case MISSING_NAME:
                            log.info("Invalid entry, missing name, skipping");
                            e.report.failure("Common name is missing");
                            statusColor = red;
                            break;
                        case MISSING_TYPE:
                            log.info("Invalid entry for name "+e.name+", skipping");
                            e.report.failure("Type is missing");
                            statusColor = red;
                            break;
                        case MISMATCH_TYPE:
                            log.info("Entry type does not match SynBioHub");
                            e.report.failure("Entry type does not match SynBioHub");
                            statusColor = red;
                            break;
                        case INVALID_TYPE:
                            log.info("Invalid entry for name "+e.name+", skipping");
                            e.report.failure("Type must be one of "+ typeTabs.get(e.tab).toString());
                            statusColor = red;
                            break;
                        case TYPE_IN_ROLE:
                            log.info("Chebi type is role");
                            e.report.failure("CHEBI type is in role");
                            statusColor = red;
                            break;
                        case DUPLICATE_VALUE:
                            log.info("Invalid entry for name "+e.name+", skipping");
                            e.report.failure(e.statusLog);
                            statusColor = red;
                            break;
                        case SBH_CONNECTION_FAILED:
                            log.warning("SynBioHub failure");
                            statusColor = gray;
                            break;
                        case GOOGLE_SHEETS_CONNECTION_FAILED:
                            statusColor = gray;
                            break;
                        default:
                            statusColor = red;
                            break;
                        }
                    }

                    e.statusColor = statusColor;

                    updatedEntryMap.put(e.row_index, e);
                }

                failuresToEmail.addAll(findFailuresToEmail(spreadsheetEntries, soonestNotifyTime));

                // Check for deleted cells that caused column values to shift up
                // If a deleted cell is found, an exception will be thrown
                checkShifts(spreadsheetEntries, synBioHubEntries);

                // List of requests to re-read spreadsheet rows
                List<String> rowRanges = new ArrayList<>();

                // Lock tab to prevent race conditions before updating
                rangeId = DictionaryAccessor.protectTab(tab);

                // Make sure columns have not moved
                Map<String, Integer> oldHeaderMap =
                    DictionaryAccessor.getDictionaryHeaders(tab);

                DictionaryAccessor.cacheTabHeaders(tab);

                Map<String, Integer> newHeaderMap =
                    DictionaryAccessor.getDictionaryHeaders(tab);

                for(String header : oldHeaderMap.keySet()) {
                    int oldIndex = oldHeaderMap.get(header);
                    int newIndex = newHeaderMap.get(header);

                    if(oldIndex != newIndex) {
                        throw new Exception("Column " + header + " on tab \"" +
                                            tab + "\" moved during processing");
                    }
                }

                // Get formatting information for Status column
                List<CellFormat> cellFormatList =
                                DictionaryAccessor.getColumnFormatting(tab, "Status");

                // Determine which rows either have changed or are invalid
                for(DictionaryEntry e : spreadsheetEntries) {
                    if(e.changed || (e.statusCode != StatusCode.VALID) ||
                       e.dictionaryEntryChanged) {
                        String rowRange = tab + "!" + e.row_index + ":" + e.row_index;
                        rowRanges.add(rowRange);
                    } else {
                        // This checks to see if the color of the text
                        // in the Status field changed.  If the color
                        // changed, the row is added to rowRanges,
                        // causing it to be updated in the spreadsheet
                        // (in particular the Status field will be
                        // updated in the spreadsheet).
                        if(cellFormatList.size() >= e.row_index) {
                            CellFormat format = cellFormatList.get(e.row_index-1);
                            if(format != null) {
                                Color statusColor =
                                    format.getTextFormat().getForegroundColor();
                                if((statusColor != null) && (e.statusColor != null)) {
                                    if(!colorsEqual(statusColor, e.statusColor)) {
                                        String rowRange = tab + "!" + e.row_index
                                            + ":" + e.row_index;
                                        rowRanges.add(rowRange);
                                    }
                                }
                            }
                        }
                    }
                }

                // Maps row index to current row contents
                Map<Integer, DictionaryEntry> currentEntryMap = new TreeMap<>();

                // Read contents of rows that need to updated, in
                // order to ensure they have not changed during
                // processing.
                if(!rowRanges.isEmpty()) {
                    // Re-read spreadsheet rows that are about to be
                    // updated
                    List<ValueRange> valueRanges = DictionaryAccessor.batchGet(rowRanges);

                    // Create dictionary entries from the rows
                    for(ValueRange valueRange : valueRanges) {
                        String rowStr = valueRange.getRange();
                        rowStr = rowStr.split("!")[1];
                        rowStr = rowStr.split(":")[0];
                        rowStr = rowStr.substring(1);

                        // Create a dictionary entry what is currently
                        // in the spreadsheet
                        int row = (int)Integer.parseInt(rowStr);
                        DictionaryEntry currentEntry =
                            new DictionaryEntry(tab, newHeaderMap, row,
                                                valueRange.getValues().get(0));

                        currentEntryMap.put(row, currentEntry);
                    }
                }

                // Loop through entries that have either been updated
                // or are invalid
                for(Integer row : currentEntryMap.keySet()) {
                    DictionaryEntry e = updatedEntryMap.get( row );

                    // This is the entry before it was processed
                    DictionaryEntry initialEntry = initialEntryMap.get(e.row_index);

                    // This entry represents the spreadsheet contents
                    // of the entry row after processing
                    DictionaryEntry currentEntry = currentEntryMap.get(e.row_index);

                    // Make sure entry was not edited during processing
                    if(!initialEntry.equals(currentEntry)) {
                        continue;
                    }

                    // Add any queued up spreadsheet updates
                    // associated with this entry
                    spreadsheetUpdates.addAll( e.spreadsheetUpdates );

                    if(e.changed) {
                        // Commit changes to SynBioHub
                        try {
                            SynBioHubAccessor.update(e.document);
                            e.report.success("Synchronized with SynBioHub");
                            ++mod_count;

                        } catch(Exception exception) {
                            e.report.failure("Failed to synchronize with SynBioBub");
                            e.statusColor = gray;
                            io_failure_count++;
                        }

                    } else if((e.statusCode == StatusCode.SBH_CONNECTION_FAILED) ||
                              (e.statusCode == StatusCode.GOOGLE_SHEETS_CONNECTION_FAILED)) {
                        io_failure_count++;

                    } else if(e.statusCode != StatusCode.VALID) {
                        bad_count++;
                    }

                    spreadsheetUpdates.add(DictionaryAccessor.writeEntryNotes(e, e.report.toString()));
                    statusFormattingUpdates.add( e.setColor("Status", e.statusColor) );

                }

                // Commit updates to spreadsheet
                if(!spreadsheetUpdates.isEmpty()) {
                    log.info("Updating " + tab + " tab in spreadsheet");
                    batchUpdateValues(spreadsheetUpdates);
                }

                // Commit formatting updates to spreadsheet
                if(!statusFormattingUpdates.isEmpty()) {
                    batchUpdateRequests(statusFormattingUpdates);
                }

                report.success(spreadsheetEntries.size()+" entries", true);
                report.success(mod_count+" modified",true);
                if(bad_count>0) {
                    report.failure(bad_count+" invalid", true);
                }
                if(io_failure_count > 0) {
                    report.failure(io_failure_count+" I/O failures", true);
                }
            } catch(Exception e) {
                e.printStackTrace();
                report.failure("Dictionary update failed: " + e.getMessage());
            }

            try {
                if(rangeId >= 0) {
                    DictionaryAccessor.unprotectRange(rangeId);
                    rangeId = -1;
                }

            } catch(Exception e) {
                log.warning("Failed to unprotect tab \"" + tab + "\"");
            }

            try {
                DictionaryAccessor.writeStatusUpdate(tab,
                                                     "SD2 Dictionary ("
                                                     + DictionaryMaintainerApp.VERSION
                                                     + ") "
                                                     + report.toString());

            } catch(Exception e2) {
                e2.printStackTrace();
            }
        }

        try {
            // Process Mapping Failures Tab in the spreadsheet
            log.info("Processing Mapping Failures ...");
            processMappingFailures(allTabEntries);
            log.info("Finished processing Mapping Failures");
        } catch(Exception e) {
            e.printStackTrace();
        }

        try {
            // Periodically send email message about entry failures
            sendEntryFailureEmails(failuresToEmail);
        } catch(Exception e) {
            e.printStackTrace();
        }

        log.info("Completed certification of dictionary");
    }
}
