_assertNotNull(_link("linkByHtml"));
_assertNotNull(_link("linkById"))
_assertEqual(_link("linkById"), _link("linkByContent"))
_click(_link("Back"));
_assertTrue(_containsHTML(document.body, "Sahi Tests"))