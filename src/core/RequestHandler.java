package core;

import java.util.Date;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.jdom2.Document;
import org.jdom2.Element;

import DAOS.DAOFactory;
import DAOS.DAOPlanification;
import data.Container;
import data.ContainerSystem;
import data.Conteneur;
import data.GeoCoordinate;
import data.Ilot;
import data.Ilotdepassage;
import data.Itineraire;
import data.Planification;

/** @file
 * 
 * Read XML request from client, do appropriate operations and prepare XML response
 * 
 * <h2>Requests format</h2>
 * <pre>
 *   <request>
 *     <type>request_type</type>
 *     <... specific elements for the request>
 *   </request>
 * </pre>
 * 
 * request_type is one of the following keywords:
 * - CONTAINER_REPORT
 *
 * <h2>Responses format</h2>
 * <pre>
 *   <response>
 *     <type>response_type</type>
 *     <... specific elements for the response>
 *   </response>
 * </pre>
 * 
 * response_type is one of the following keywords:
 * - OK
 * - ERROR
 */

public class RequestHandler {
	private static Logger LOGGER = Logger.getLogger(RequestHandler.class.getName());

	private static final GeoCoordinate depot = new GeoCoordinate(43.602704, 1.441745); // Toulouse center
	
	private int clientNumber;

	public RequestHandler(int clientNumber) {
		super();
		this.clientNumber = clientNumber;
	}

	// process request and provide response
	public Document handle(Document request) {
		Element rootResp = new Element("response");
		Document response = new Document(rootResp);

		Element rootReq = request.getRootElement();
		String requestType = rootReq.getChild("request_type").getTextNormalize().toUpperCase();
		LOGGER.info("Client #"+clientNumber+" request: "+ requestType);
		switch(requestType)
		{
			case "CONTAINER_REPORT":
				handleContainerReport(rootReq, rootResp);
				break;
				
			case "REQ_SUPERVISION_STATE":
				handleReqSupervisionState(rootReq, rootResp);
				break;
				
			case "TRIG_CIRCUIT_COMPUTATION":
				handleTrigCircuitComputation(rootReq, rootResp);
				break;
				
			case "REQ_CIRCUITS":
				handleReqCircuits(rootReq, rootResp);
				break;
				
			case "REQ_CIRCUIT":
				handleReqCircuit(rootReq, rootResp);
				break;
				
			default:
				LOGGER.warning("Client #"+clientNumber+" unsupported request: "+requestType);
				buildResponseType(rootResp, "ERROR");
				break;
		}
		
		return response;
	}
	
	private void handleContainerReport(Element rootReq, Element rootResp) {
		// get container ID
		Element eltContRep = rootReq.getChild("container_report");
		int containerId = Integer.valueOf(eltContRep.getChild("id").getTextNormalize());
		
		// get container object associated to this ID
		Container container = ContainerSystem.getContainerSystem().getContainer(containerId);
		
		// update container state
		container.setState(
				Integer.valueOf(eltContRep.getChild("weight").getTextNormalize()),
				Integer.valueOf(eltContRep.getChild("volume").getTextNormalize()),
				Integer.valueOf(eltContRep.getChild("volumemax").getTextNormalize())
		);

		buildResponseType(rootResp, "OK");
	}
	
	private void handleReqSupervisionState(Element rootReq, Element rootResp) {
		Element eltSupervisionState = new Element("supervision_state");
		
		//[Kevin] : envoi de date au client container supprim� et remplac� par un entier al�atoire
		// � terme, il faudra renvoyer les donn�es pr�sentes dans la base en fonction de l'ID du container qui � envoy� la requ�te 
		addField(eltSupervisionState, "date_state", Integer.toString((int)(Math.random() * (100 - 0))));
		
		Element eltContainerSets = new Element("container_sets");
		eltSupervisionState.addContent(eltContainerSets);
		
		try {
			for (Ilot ilot: DAOFactory.creerDAOIlot().select()) {
				Element eltContainerSet = new Element("container_set");
				eltContainerSets.addContent(eltContainerSet);
				
				addLocation(eltContainerSet, "location", ilot.getLocation());
				addFieldBool(eltContainerSet, "to_be_collected", ilot.isReadyForCollect());
				
				Element eltContainers = new Element("containers");
				eltContainerSet.addContent(eltContainers);
				for (Conteneur conteneur: ilot.get_conteneurs()) {
					Element eltContainer = new Element("container");
					eltContainers.addContent(eltContainer);
					
					addFieldInt(eltContainer, "id", conteneur.get_id());
					addFieldInt(eltContainer, "weight", conteneur.get_lastpoids());
					addFieldInt(eltContainer, "volume", conteneur.get_lastvolume());
					addFieldInt(eltContainer, "volumemax", conteneur.get_volumemax());
					addFieldInt(eltContainer, "fillratio", conteneur.get_fillratio());
					addFieldBool(eltContainer, "to_be_collected", conteneur.isReadyForCollect());
				}
			}
		} catch (Exception e) {
			LOGGER.log(Level.SEVERE, "Error connecting to DB", e);
			buildResponseType(rootResp, "ERROR");
			return;
		}

		buildResponseType(rootResp, "RESP_SUPERVISION_STATE");
		rootResp.addContent(eltSupervisionState);
	}
	
	private void handleTrigCircuitComputation(Element rootReq, Element rootResp) {
		ContainerSystem.getContainerSystem().trigCircuitComputation();
		buildResponseType(rootResp, "OK");
	}
	
	private static void addLocation(Element eltRoot, String fieldname, GeoCoordinate geoCoord) {
		Element eltLocation = new Element(fieldname);
		eltRoot.addContent(eltLocation);
		addFieldDouble(eltLocation, "latitude", geoCoord.getLatitude());
		addFieldDouble(eltLocation, "longitude", geoCoord.getLongitude());
	}

	private void handleReqCircuits(Element rootReq, Element rootResp) {
		Element eltCircuits = new Element("circuits");
		try {
			DAOPlanification daoplanification2 = DAOFactory.creerDAOPlanification();
			Planification pla = daoplanification2.selectbydate(new Date());

			for (int i = 0; i < pla.get_itineraires().size(); i++) {
				eltCircuits.addContent(getItineraire(i, pla.get_itineraires().get(i)));
			}
		} catch (Exception e) {
			LOGGER.log(Level.SEVERE, "Error connecting to DB", e);
			buildResponseType(rootResp, "ERROR");
			return;
		}
		buildResponseType(rootResp, "RESP_CIRCUITS");
		rootResp.addContent(eltCircuits);

		// liste des ilots non collectés: pas implémenté
		Element eltNotCollected = new Element("not_collected");
		eltCircuits.addContent(eltNotCollected);
		eltNotCollected.addContent(new Element("container_sets"));
	}
	
	private void handleReqCircuit(Element rootReq, Element rootResp) {
/*		int circuitIndex = Integer.valueOf(rootReq.getChild("circuit").getChildTextNormalize("index"));
		if (circuitIndex < 0 || circuitIndex >= ContainerSystem.getContainerSystem().getCollectRoutes().size())
		{
			buildResponseType(rootResp, "ERROR"); // invalid circuit index
			return;
		}
		
		buildResponseType(rootResp, "RESP_CIRCUIT");
		rootResp.addContent(getCircuit(circuitIndex));*/
		buildResponseType(rootResp, "ERROR");
	}
	
	private Element getItineraire(int circuitIndex, Itineraire iti) {
		Element eltCircuit = new Element("circuit");
		addFieldInt(eltCircuit, "index", circuitIndex);
		addLocation(eltCircuit, "depot_location", depot);
		Element eltContainerSets = getIlotsdepassage(iti.get_ilotsdepassage()); 
		eltCircuit.addContent(eltContainerSets);
		return eltCircuit;
	}
	
	private Element getIlotsdepassage(List<Ilotdepassage> ilotsdepassage) {
		Element eltContainerSets = new Element("container_sets");
		
		for (Ilotdepassage ilotdepassage: ilotsdepassage) {
			Element eltContainerSet = new Element("container_set");
			eltContainerSets.addContent(eltContainerSet);
			Ilot ilot = ilotdepassage.get_Ilot();
			
			addLocation(eltContainerSet, "location", ilot.getLocation());
			
			Element eltContainers = new Element("containers");
			eltContainerSet.addContent(eltContainers);
			for (Conteneur conteneur: ilot.get_conteneurs()) {
				Element eltContainer = new Element("container");
				eltContainers.addContent(eltContainer);
				
				addFieldInt(eltContainer, "id", conteneur.get_id());
			}
		}
		return eltContainerSets;
	}
	
	private void buildResponseType(Element rootResp, String responseType) {
		LOGGER.info("Client #"+clientNumber+" response: "+ responseType);
		addField(rootResp, "response_type", responseType);
	}
	
	private static void addField(Element eltRoot, String fieldname, String value) {
		Element elt = new Element(fieldname);
		elt.setText(value);
		eltRoot.addContent(elt);
	}
	
	private static void addFieldInt(Element eltRoot, String fieldname, int value) {
		addField(eltRoot, fieldname, String.valueOf(value));
	}
	
	private static void addFieldBool(Element eltRoot, String fieldname, boolean value) {
		addField(eltRoot, fieldname, String.valueOf(value));
	}
	
	private static void addFieldDouble(Element eltRoot, String fieldname, double value) {
		addField(eltRoot, fieldname, String.valueOf(value));
	}
}
