package org.teiid.resource.adapter.google.metadata;


import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

/**
 * Metadata about specific spreadsheet.
 *
 */
public class SpreadsheetInfo {
	private String spreadsheetName;
	private Map<String, Worksheet> worksheetByName = new HashMap<String,Worksheet>();
	
	public SpreadsheetInfo(String spreadsheetName) {
		this.spreadsheetName = spreadsheetName;
		
	}
	
	public Worksheet createWorksheet(String title) {
		Worksheet worksheet = new Worksheet(title);
		worksheetByName.put(title, worksheet);
		return worksheet;
	}
	
	public Collection<Worksheet> getWorksheets(){
		return Collections.unmodifiableCollection(worksheetByName.values());
	}
	
	public Worksheet getWorksheetByName(String worksheet){		
		return worksheetByName.get(worksheet);		
	}
	
	public String getSpreadsheetName() {
		return spreadsheetName;
	}
	
	
}
