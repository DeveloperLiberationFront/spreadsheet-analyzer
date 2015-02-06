package net.barik;

import java.io.IOException;
import java.io.InputStream;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.apache.poi.POIXMLDocument;
import org.apache.poi.openxml4j.exceptions.InvalidFormatException;
import org.apache.poi.openxml4j.opc.OPCPackage;
import org.apache.poi.poifs.eventfilesystem.POIFSReader;
import org.apache.poi.poifs.eventfilesystem.POIFSReaderEvent;
import org.apache.poi.poifs.eventfilesystem.POIFSReaderListener;
import org.apache.poi.poifs.filesystem.POIFSFileSystem;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.DateUtil;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import org.apache.poi.ss.util.CellRangeAddress;
import org.apache.poi.ss.util.CellReference;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;

public class SpreadsheetAnalyzer {
	
	private Workbook workbook;
	
	private Map<SheetLocation, InputCellReferencePackage> inputCellByReferenceMap = new HashMap<>();
	private Map<String, Set<InputCellReferencePackage>> inputCellMap = new HashMap<>();		//maps sheet name to inputCellPackage
	
	private Map<SheetLocation, CellReferencePackage> formulaCellByReferenceMap = new HashMap<>();
	private Map<String, Set<CellReferencePackage>> formulaCellMap = new HashMap<>();		//maps sheet name to formulaCellPackage
	
	private Map<InputCellType, Integer> inputCellCounts = new EnumMap<>(InputCellType.class);
	private Map<String, Integer> functionCounts = new HashMap<>();
	private Map<FunctionEvalType, Integer> evalTypeCounts = new EnumMap<>(FunctionEvalType.class);
	private boolean containsMacros = false;
	
	private final Pattern findFunctions = Pattern.compile("\\p{Upper}+\\(");
	private final Pattern findPotentialCellReferences = Pattern.compile("[^+-.,}{><();\\\\/*\'\\\"~]+");

	private Sheet currentSheet;

	private InputCellType lastInputCellType;

	private int formulasThatReferenceOtherCells;

	private Map<InputCellType, Integer>  referencedInputCells;

	private int formulasReferencedByOtherCells;

	private SpreadsheetAnalyzer(Workbook wb) {
		this.workbook = wb;
	}

	public static SpreadsheetAnalyzer doEUSESAnalysis(InputStream is, InputStream is2) throws InvalidFormatException, IOException {
		SpreadsheetAnalyzer analyzer = new SpreadsheetAnalyzer(WorkbookFactory.create(is));
		
		analyzer.analyzeEUSESMetrics(is2);

		return analyzer;
	}
	
	private static Integer incrementOrInitialize(Integer i) {
		if (i == null) {
			return 1;
		}
		return i + 1;
	}


	private void analyzeEUSESMetrics(InputStream is2) throws IOException {
		clearPreviousMetrics();

		checkForMacros(is2);
		
		findInputCells();
		
		findReferencedCells();
	}

	private void checkForMacros(InputStream is2) throws IOException {
		if (POIFSFileSystem.hasPOIFSHeader(is2)){
			//Looking at HSSF
			POIFSReader r = new POIFSReader();
			MacroListener ml = new MacroListener();
			r.registerListener(ml);
			r.read(is2);
			this.containsMacros = ml.isMacroDetected();
		}	
		else if (POIXMLDocument.hasOOXMLHeader(is2)) {
           	this.containsMacros = false;
        }
		else {
			throw new IllegalArgumentException("Your InputStream was neither an OLE2 stream, nor an OOXML stream");
		}	
	}
			

	private void findInputCells() {
		for(int i = 0; i< workbook.getNumberOfSheets();i++) {
			currentSheet = workbook.getSheetAt(i);
			Iterator <Row> rowIterator = currentSheet.iterator();
			while(rowIterator.hasNext()){
				Row row = rowIterator.next();
				Iterator<Cell> cellIterator = row.cellIterator();
				while(cellIterator.hasNext()) {
					Cell cell = cellIterator.next();
					this.lastInputCellType = null;

					switch(cell.getCellType()) {
					case Cell.CELL_TYPE_BOOLEAN:
					case Cell.CELL_TYPE_STRING:
					case Cell.CELL_TYPE_ERROR:
					case Cell.CELL_TYPE_NUMERIC:
						handleInputCell(cell);
						break;

					case Cell.CELL_TYPE_FORMULA:
						handleFormulas(cell);
						break;
					}

					if (lastInputCellType != null) {
						inputCellCounts.put(lastInputCellType,
								incrementOrInitialize(inputCellCounts.get(lastInputCellType)));

						InputCellReferencePackage inputCellPackage = new InputCellReferencePackage(cell, lastInputCellType);
						Set<InputCellReferencePackage> oldSet = inputCellMap.get(currentSheet.getSheetName());
						if (oldSet == null) {
							oldSet = new HashSet<>();
							inputCellMap.put(currentSheet.getSheetName(), oldSet);
						}
						
						oldSet.add(inputCellPackage);
						
						inputCellByReferenceMap.put(new SheetLocation(cell), inputCellPackage);
					}
				}

			}
		}
	}

	private FunctionEvalType getAndConvertCachedType(Cell cell){
		//Helper for handling evaluation types can return BLANK
		if (cell.getCachedFormulaResultType() == Cell.CELL_TYPE_NUMERIC) {
			if (DateUtil.isCellDateFormatted(cell)) {
				return FunctionEvalType.DATE;
			}
			double d = cell.getNumericCellValue();
	    	if (Math.rint(d) == d) {  //integer check from http://stackoverflow.com/a/9898613/1447621
	    		return FunctionEvalType.INTEGER;
	    	} else {
	    		return FunctionEvalType.NON_INTEGER_NUMBER;
	    	}
		} else {
			return FunctionEvalType.fromCellType(cell.getCachedFormulaResultType());
		}
	}
	
	private void handleFormulas(Cell cell) {
		addFormulaToReferenceMaps(cell);
		
    	//Formula cell evaluation type
		FunctionEvalType evaluatingType = getAndConvertCachedType(cell);
		if (evaluatingType != null){ //Null signals function or blank from call to fromCellType
			evalTypeCounts.put(evaluatingType,incrementOrInitialize(evalTypeCounts.get(evaluatingType)));
		}
		
		String functionString = cell.getCellFormula();
    	if (functionString.startsWith("#")) {
    		lastInputCellType = InputCellType.ERROR;
		} else {
			findFunctionsUsed(functionString);
		}
	}

	private void addFormulaToReferenceMaps(Cell cell) {
		CellReferencePackage inputCellPackage = new CellReferencePackage(cell);
		Set<CellReferencePackage> oldSet = formulaCellMap.get(currentSheet.getSheetName());
		if (oldSet == null) {
			oldSet = new HashSet<>();
			formulaCellMap.put(currentSheet.getSheetName(), oldSet);
		}
		oldSet.add(inputCellPackage);
		formulaCellByReferenceMap.put(new SheetLocation(cell), inputCellPackage);
	}

	private void findFunctionsUsed(String functionString) {
		Matcher m = findFunctions.matcher(functionString);
		while(m.find()) {
			String function = m.group();
			function = function.substring(0, function.length()-1);
			functionCounts.put(function, incrementOrInitialize(functionCounts.get(function)));
		}
	}

	private void handleInputCell(Cell cell) {
		
		if (cell.getCellType() == Cell.CELL_TYPE_NUMERIC) {
			if (DateUtil.isCellDateFormatted(cell)) {
				lastInputCellType = InputCellType.DATE;
				return;
			}
			double d = cell.getNumericCellValue();
	    	if (Math.rint(d) == d) {  //integer check from http://stackoverflow.com/a/9898613/1447621
	    		lastInputCellType = InputCellType.INTEGER;
	    	} else {
	    		lastInputCellType = InputCellType.NON_INTEGER_NUMBER;
	    	}
		} else {
			lastInputCellType = InputCellType.fromCellType(cell.getCellType());
		}
	}

	private void clearPreviousMetrics() {
		functionCounts.clear();
		inputCellCounts.clear();
		inputCellMap.clear();
		evalTypeCounts.clear();
		containsMacros = false;
		formulasThatReferenceOtherCells = 0;
		formulasReferencedByOtherCells = 0;
	}

	private void findReferencedCells() {
		// pass through all formula cells again and look for references
		for(int i = 0; i< workbook.getNumberOfSheets();i++) {
			currentSheet = workbook.getSheetAt(i);
			Iterator <Row> rowIterator = currentSheet.iterator();
			while(rowIterator.hasNext()){
				Row row = rowIterator.next();
				Iterator<Cell> cellIterator = row.cellIterator();
				while(cellIterator.hasNext()) {
					Cell cell = cellIterator.next();

					if(cell.getCellType()==Cell.CELL_TYPE_FORMULA) {
						processFormulaReferences(cell);
					}
				}
			}
		}
		condenseInputCellReferencesFromAllSheets();
		countFormulaCellsReferenced();
	}

	private void countFormulaCellsReferenced() {
		for(CellReferencePackage formulaPackage : formulaCellByReferenceMap.values()) {
			if (formulaPackage.isReferenced)
				formulasReferencedByOtherCells++;
		}
		
	}

	private void processFormulaReferences(Cell cell) {
		String formula = cell.getCellFormula();
		//we look for anything that might be a cell reference and use
		// POI's parsers to see if they are valid references
		Matcher m = findPotentialCellReferences.matcher(formula);
		
		boolean wasThereAReference = false;
		while (m.find()) {
			String maybeCell = m.group();
			try {
				//look for colon to detect range
				if (maybeCell.indexOf(':') == -1) {
					if (maybeCell.matches("[A-Z]+")) {	// skip LOG, SUM and other functions
						continue;
					}
					CellReference cr = new CellReference(maybeCell);
					wasThereAReference = true;
					
					checkInputCellReferences(cr);
					checkFormulaCellReferences(cr);
				}
				else {
					CellRangeAddress cra = CellRangeAddress.valueOf(maybeCell);
					wasThereAReference = true;
					int index = maybeCell.indexOf('!');
					String sheetName;
					if (index != -1) {
						 sheetName = maybeCell.substring(0, index);
					} else {
						// otherwise, we are in the current sheet.
						sheetName = cell.getSheet().getSheetName();
					}
					
					checkInputCellReferences(cra, sheetName);
					checkFormulaCellReferences(cra, sheetName);
				}
			} catch (Exception e) {
				System.out.println("Failed for " + maybeCell);
			}
		}
		if (wasThereAReference) {
			formulasThatReferenceOtherCells++;
		}
	}

	private void checkInputCellReferences(CellRangeAddress cra, String sheetName) {
		checkReferences(cra, inputCellMap.get(sheetName));
	}
	
	private void checkFormulaCellReferences(CellRangeAddress cra, String sheetName) {
		checkReferences(cra, formulaCellMap.get(sheetName));
	}

	private void checkReferences(CellRangeAddress cra, Set<? extends CellReferencePackage> set) {
		if (set != null) {
			for(CellReferencePackage p : set) {
				if (cra.isInRange(p.cell.getRowIndex(), p.cell.getColumnIndex())) {
					p.isReferenced = true;
				}
			}
		}
	}

	private void checkInputCellReferences(CellReference cr) {
		CellReferencePackage p = inputCellByReferenceMap.get(new SheetLocation(cr));
		if (p != null) {
			p.isReferenced = true;
		}
	}
	
	private void checkFormulaCellReferences(CellReference cr) {
		CellReferencePackage p = formulaCellByReferenceMap.get(new SheetLocation(cr));
		if (p != null) {
			p.isReferenced = true;
		}
	}

	private void condenseInputCellReferencesFromAllSheets() {
		referencedInputCells = new EnumMap<>(InputCellType.class);
		
		for(Set<InputCellReferencePackage> set: inputCellMap.values()) {
			for(InputCellReferencePackage p : set) {
				if (p.isReferenced) {
					referencedInputCells.put(p.type, incrementOrInitialize(referencedInputCells.get(p.type)));
				}
			}
		}
	}

	public boolean getContainsMacro(){
		return containsMacros;
	}
	
	public Map<String, Integer> getFunctionCounts() {
		return functionCounts;
	}

	public Map<InputCellType, Integer> getInputCellCounts() {
		return inputCellCounts;
	}
	
	public Map<FunctionEvalType, Integer> getFormulaCellCounts() {
		return evalTypeCounts; 
	}


	public Map<InputCellType, Integer> getInputReferences() {		
		return referencedInputCells;
	}

	public enum FunctionEvalType {
		//Includes Blank
		INTEGER,BOOLEAN,DATE,ERROR,NON_INTEGER_NUMBER,STRING, BLANK;

		public static FunctionEvalType fromCellType(int cellType) {
			switch (cellType) {
			case Cell.CELL_TYPE_BOOLEAN:
				return BOOLEAN;
			case Cell.CELL_TYPE_ERROR:
				return ERROR;
			case Cell.CELL_TYPE_STRING:
				return STRING;
			case Cell.CELL_TYPE_BLANK:
				return BLANK;
			}
			return null;
		}
	}
	
	public enum InputCellType {
		INTEGER,BOOLEAN,DATE,ERROR,NON_INTEGER_NUMBER,STRING;

		public static InputCellType fromCellType(int cellType) {
			switch (cellType) {
			case Cell.CELL_TYPE_BOOLEAN:
				return BOOLEAN;
			case Cell.CELL_TYPE_ERROR:
				return ERROR;
			case Cell.CELL_TYPE_STRING:
				return STRING;
			}
			return null;
		}
	}

	private static class CellReferencePackage {
		public Cell cell;
		public boolean isReferenced;
		
		
		public CellReferencePackage(Cell cell) {
			this.cell = cell;
		}

	}
	
	private static class InputCellReferencePackage extends CellReferencePackage {
		public InputCellType type;
		
		public InputCellReferencePackage(Cell cell, InputCellType type) {
			super(cell);
			this.type = type;
		}
	}
	
	private static class SheetLocation {
		private final String s; 

		public SheetLocation(Cell c) {
			s = c.getSheet().getSheetName() +"!"+ c.getColumnIndex() +","+ c.getRowIndex();
		}

		public SheetLocation(CellReference cr) {
			s = cr.getSheetName() +"!"+ cr.getCol() +","+cr.getRow();
		}

		@Override
		public int hashCode() {
			final int prime = 31;
			int result = 1;
			result = prime * result + ((s == null) ? 0 : s.hashCode());
			return result;
		}


		@Override
		public boolean equals(Object obj) {
			if (this == obj)
				return true;
			if (obj == null)
				return false;
			if (getClass() != obj.getClass())
				return false;
			SheetLocation other = (SheetLocation) obj;
			if (s == null) {
				if (other.s != null)
					return false;
			} else if (!s.equals(other.s))
				return false;
			return true;
		}
		
			
	}
	
	public class MacroListener implements POIFSReaderListener {
			//From http://www.rgagnon.com/javadetails/java-detect-if-xls-excel-file-contains-a-macro.html
		  boolean macroDetected = false;

		  public boolean isMacroDetected() {
		    return macroDetected;
		  }

		  public void processPOIFSReaderEvent(POIFSReaderEvent event) {
		    if(event.getPath().toString().startsWith("\\Macros")
		          || event.getPath().toString().startsWith("\\_VBA")) {
		      macroDetected = true;
		    }
		  }
		}

	public int getFormulaReferencingOtherCells() {
		return formulasThatReferenceOtherCells;
	}

	public int getFormulasReferenced() {
		return formulasReferencedByOtherCells;
	}
}
