// Joey : repère de l'avant du corps (une flèche plate à coller dans l'axe du Roomba, la pointe vers l'avant).
// La caméra, tête baissée, la voit en entier : son axe donne l'angle entre la tête et le corps.
longueur_tige = 33;   // mm
largeur_tige  = 6;
longueur_pointe = 12;
largeur_pointe  = 14;
epaisseur = 1.6;
linear_extrude(height = epaisseur)
  polygon([[0, -largeur_tige/2], [longueur_tige, -largeur_tige/2], [longueur_tige, -largeur_pointe/2],
           [longueur_tige + longueur_pointe, 0], [longueur_tige, largeur_pointe/2], [longueur_tige, largeur_tige/2],
           [0, largeur_tige/2]]);
